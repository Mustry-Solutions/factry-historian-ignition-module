package io.factry.historian.gateway;

import com.google.protobuf.Value;
import com.inductiveautomation.historian.common.model.data.AtomicPoint;
import com.inductiveautomation.historian.common.model.data.DataPointType;
import com.inductiveautomation.historian.common.model.options.RawQueryKey;
import com.inductiveautomation.historian.common.model.options.RawQueryOptions;
import com.inductiveautomation.historian.gateway.api.query.processor.ProcessingContext;
import com.inductiveautomation.historian.gateway.api.query.processor.RawPointProcessor;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import io.factry.historian.proto.QueryTimeseriesRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static io.factry.historian.gateway.FactryQueryEngine.RAW_QUERY_PAGE_SIZE;

/** Raw reads through the public query entry point, against an in-process Factry. */
class FactryQueryEngineRawTest {

    private static final long START = 1_794_268_800_000L; // 2026-11-10T00:00:00Z
    private static final long DAY = 86_400_000L;

    private FakeFactry factry;
    private FactryGrpcClient client;
    private FactryQueryEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        factry = new FakeFactry();
        client = factry.client();
    }

    @AfterEach
    void tearDown() throws Exception {
        client.shutdown();
        factry.close();
    }

    private void startEngine() {
        FactryHistorianSettings settings = new FactryHistorianSettings();
        settings.setDelimiter("/");
        MeasurementCache cache = new MeasurementCache();
        cache.refresh(client);
        engine = new FactryQueryEngine(FakeFactry.gatewayContext(), "Factry", settings, client, cache,
                new HistorianMetrics());
    }

    private static RawQueryKey key(String tag) {
        return new RawQueryKey(QualifiedPath.parseSafe("sys:gw:/prov:default:/tag:" + tag));
    }

    /** Records what the engine hands to Ignition. */
    static final class Recorder implements RawPointProcessor {
        final Map<RawQueryKey, List<AtomicPoint<?>>> points = new LinkedHashMap<>();
        Exception error;
        QualityCode errorCode;
        boolean completed;

        @Override
        public boolean onInitialize(ProcessingContext<RawQueryKey, DataPointType> context) {
            return true;
        }

        @Override
        public boolean onPointAvailable(RawQueryKey key, AtomicPoint<?> point) {
            points.computeIfAbsent(key, k -> new ArrayList<>()).add(point);
            return true;
        }

        @Override
        public void onKeyFailure(RawQueryKey key, QualityCode quality) {
        }

        @Override
        public void onError(QualityCode quality) {
            errorCode = quality;
        }

        @Override
        public void onError(Exception e) {
            error = e;
        }

        @Override
        public void onComplete() {
            completed = true;
        }

        int count(RawQueryKey key) {
            return points.getOrDefault(key, List.of()).size();
        }
    }

    private Recorder query(Integer returnSize, RawQueryKey... keys) {
        RawQueryOptions.Builder b = RawQueryOptions.builder().startTime(START).endTime(START + DAY);
        for (RawQueryKey k : keys) {
            b.addQueryKey(k);
        }
        if (returnSize != null) {
            b.returnSize(returnSize);
        }
        Recorder rec = new Recorder();
        engine.query(b.build(), rec);
        return rec;
    }

    @Test
    void dayOfSecondDataForThreePensComesBackComplete() {
        // About 5 MB in one answer: more than grpc-java's default 4 MiB message limit.
        for (String tag : List.of("a", "b", "c")) {
            String uuid = factry.addMeasurement("default/" + tag, "number");
            for (int s = 0; s < 86_400; s++) {
                factry.addPoint(uuid, START + s * 1000L, s);
            }
        }
        startEngine();

        Recorder rec = query(null, key("a"), key("b"), key("c"));

        assertNull(rec.error, () -> "read failed: " + rec.error);
        assertTrue(rec.completed);
        for (String tag : List.of("a", "b", "c")) {
            List<AtomicPoint<?>> pts = rec.points.get(key(tag));
            assertEquals(86_400, pts.size(), tag);
            assertEquals(START, pts.get(0).timestamp().toEpochMilli());
            assertEquals(START + 86_399_000L, pts.get(pts.size() - 1).timestamp().toEpochMilli());
        }
        assertTrue(factry.queries.stream().allMatch(QueryTimeseriesRequest::hasLimit),
                "every request should be paged");
    }

    @Test
    void pagesKeepEveryStatusSeries() {
        // A Bad series much shorter than the Good one: it must not stop the Good one from paging.
        String uuid = factry.addMeasurement("default/a", "number");
        for (int s = 0; s < 25_000; s++) {
            factry.addPoint(uuid, START + s * 1000L, Value.newBuilder().setNumberValue(s).build(),
                    s % 1000 == 7 ? "Bad" : "Good");
        }
        startEngine();

        Recorder rec = query(null, key("a"));

        List<AtomicPoint<?>> pts = rec.points.get(key("a"));
        assertEquals(25_000, pts.size());
        assertEquals(25, pts.stream().filter(p -> p.quality().isNotGood()).count());
        for (int i = 0; i < pts.size(); i++) {
            assertEquals(START + i * 1000L, pts.get(i).timestamp().toEpochMilli());
        }
    }

    @Test
    void oneLargePageFitsUnderFactrysMessageLimit() {
        // 200 strings of 100 kB: 20 MB in one page, more than 4 MiB, less than Factry's 64 MiB.
        String uuid = factry.addMeasurement("default/s", "string");
        String big = "x".repeat(100_000);
        for (int s = 0; s < 200; s++) {
            factry.addPoint(uuid, START + s * 1000L, Value.newBuilder().setStringValue(s + big).build(), "Good");
        }
        startEngine();

        Recorder rec = query(null, key("s"));

        assertNull(rec.error, () -> "read failed: " + rec.error);
        assertEquals(200, rec.count(key("s")));
    }

    @Test
    void pageTooLargeForFactryIsRetriedSmaller() {
        String uuid = factry.addMeasurement("default/s", "string");
        String big = "x".repeat(10_000);
        for (int s = 0; s < 500; s++) {
            factry.addPoint(uuid, START + s * 1000L, Value.newBuilder().setStringValue(s + big).build(), "Good");
        }
        factry.maxAnswerBytes = 1_000_000;
        startEngine();

        Recorder rec = query(null, key("s"));

        assertNull(rec.error, () -> "read failed: " + rec.error);
        List<AtomicPoint<?>> pts = rec.points.get(key("s"));
        assertEquals(500, pts.size());
        for (int i = 0; i < pts.size(); i++) {
            assertEquals(i + big, pts.get(i).value());
        }
    }

    @Test
    void readStopsAtTheMaxRawPointsCap() {
        for (String tag : List.of("a", "b", "c")) {
            String uuid = factry.addMeasurement("default/" + tag, "number");
            for (int s = 0; s < 86_400; s++) {
                factry.addPoint(uuid, START + s * 1000L, s);
            }
        }
        startEngine();
        engine.maxRawPoints = 30_000;

        Recorder rec = query(null, key("a"), key("b"), key("c"));

        assertNull(rec.error, () -> "read failed: " + rec.error);
        assertTrue(rec.completed);
        // The cap is shared over the pens, and each pen keeps its first points in time.
        for (String tag : List.of("a", "b", "c")) {
            List<AtomicPoint<?>> pts = rec.points.get(key(tag));
            assertEquals(10_000, pts.size(), tag);
            assertEquals(START + 9_999_000L, pts.get(pts.size() - 1).timestamp().toEpochMilli(), tag);
        }
        assertEquals(1, factry.queries.size(), "should stop paging at the cap");
    }

    @Test
    void factryThatIgnoresOffsetDoesNotLoopOrDuplicate() {
        String uuid = factry.addMeasurement("default/a", "number");
        for (int s = 0; s < 25_000; s++) {
            factry.addPoint(uuid, START + s * 1000L, s);
        }
        factry.ignoreOffset = true;
        startEngine();

        Recorder rec = assertTimeoutPreemptively(Duration.ofSeconds(30), () -> query(null, key("a")));

        assertNull(rec.error, () -> "read failed: " + rec.error);
        assertTrue(rec.completed);
        // Only the first page is real; the second repeats it and must be dropped.
        List<AtomicPoint<?>> pts = rec.points.get(key("a"));
        assertEquals(RAW_QUERY_PAGE_SIZE, pts.size());
        for (int i = 0; i < pts.size(); i++) {
            assertEquals(START + i * 1000L, pts.get(i).timestamp().toEpochMilli());
        }
        assertEquals(2, factry.queries.size(), "should stop at the first page that does not move on");
    }

    @Test
    void returnSizeKeepsTheFirstPointsInTime() {
        for (String tag : List.of("a", "b")) {
            String uuid = factry.addMeasurement("default/" + tag, "number");
            for (int s = 0; s < 5_000; s++) {
                // A sparse Bad series that runs on long after the first 100 Good points.
                factry.addPoint(uuid, START + s * 1000L, Value.newBuilder().setNumberValue(s).build(),
                        s % 1000 == 7 ? "Bad" : "Good");
            }
        }
        startEngine();

        Recorder rec = query(100, key("a"), key("b"));

        for (String tag : List.of("a", "b")) {
            List<AtomicPoint<?>> pts = rec.points.get(key(tag));
            assertEquals(100, pts.size(), tag);
            assertEquals(START + 99_000L, pts.get(99).timestamp().toEpochMilli(), tag);
        }
        for (QueryTimeseriesRequest q : factry.queries) {
            assertTrue(q.getOffset() + q.getLimit() <= 100, () -> "fetched past returnSize: " + q);
        }
    }
}
