package io.factry.historian.gateway;

import com.inductiveautomation.historian.common.model.data.AtomicPoint;
import com.inductiveautomation.historian.common.model.data.DataPointType;
import com.inductiveautomation.historian.common.model.options.RawQueryKey;
import com.inductiveautomation.historian.common.model.options.RawQueryOptions;
import com.inductiveautomation.historian.gateway.api.query.processor.ProcessingContext;
import com.inductiveautomation.historian.gateway.api.query.processor.RawPointProcessor;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Raw reads with the value before the start and the value after the end, as Ignition's own
 * historians return them, read the way Ignition reads them (HistorianQueryInterface and
 * HistoryWriter).
 */
class FactryQueryEngineBoundsTest {

    private static final long T0 = Instant.parse("2026-11-10T10:00:00Z").toEpochMilli();
    private static final long END = T0 + 60_000;
    /** queryTagHistory's includeBoundingValues=True and False, and "no seed values". */
    private static final int YES_BOUNDS = 16;
    private static final int NO_BOUNDS = 32;
    private static final int NO_SEED = 1;

    /** Refuses the reads of the values around the range when set. */
    private boolean refuseBounds;

    private final TwaFakeFactry factry = new TwaFakeFactry() {
        @Override
        public io.factry.historian.proto.QueryTimeseriesResponse queryTimeseries(
                io.factry.historian.proto.QueryTimeseriesRequest request) {
            if (refuseBounds && request.getLimit() == 1) {
                throw io.grpc.Status.INTERNAL.withDescription("query failed").asRuntimeException();
            }
            return super.queryTimeseries(request);
        }
    };

    private FactryQueryEngine engine() {
        FactryHistorianSettings settings = new FactryHistorianSettings();
        settings.setDelimiter("/");
        MeasurementCache cache = new MeasurementCache();
        cache.refresh(factry);
        FactryQueryEngine engine = new FactryQueryEngine(TwaFakeFactry.gatewayContext(), "Factry", settings,
                factry, cache, new HistorianMetrics());
        engine.now = () -> T0 + 3_600_000;
        return engine;
    }

    private AverageHistoryQuery.Dataset raw(int returnSize, int flags, String... tags) throws Exception {
        AverageHistoryQuery.Dataset ds = AverageHistoryQuery.raw(engine(), T0, END, returnSize, flags, tags);
        assertNull(ds.error, ds::toString);
        return ds;
    }

    /** "seconds from T0 = value" per row, for the first data column. */
    private static List<String> rows(AverageHistoryQuery.Dataset ds) {
        List<String> out = new ArrayList<>();
        for (int r = 0; r < ds.rows.size(); r++) {
            out.add((ds.time(r) - T0) / 1000 + "=" + ds.rows.get(r)[1]);
        }
        return out;
    }

    /** A tag stored on change: 5 ten minutes before the range, 6 inside, 7 a minute after it. */
    private String onChangeTag(String name) {
        String u = factry.addMeasurement("default/" + name, "number");
        factry.add(u, T0 - 600_000, 5, "Good");
        factry.add(u, T0 + 30_000, 6, "Good");
        factry.add(u, T0 + 120_000, 7, "Good");
        return u;
    }

    @Test
    void theValuesAroundTheRange_areReturnedUnlessTheOptionsSayNo() throws Exception {
        onChangeTag("out");

        // Before the fix only the 30 s row came back, whatever the flags.
        for (int flags : new int[]{0, YES_BOUNDS}) {
            assertEquals(List.of("-600=5.0", "30=6.0", "120=7.0"), rows(raw(-1, flags, "out")), "flags " + flags);
        }
        for (int flags : new int[]{NO_BOUNDS, NO_SEED}) {
            assertEquals(List.of("30=6.0"), rows(raw(-1, flags, "out")), "flags " + flags);
        }
    }

    @Test
    void aRangeWithoutChanges_stillHasItsValue() throws Exception {
        String u = factry.addMeasurement("default/out", "number");
        factry.add(u, T0 - 600_000, 5, "Good");
        factry.add(u, T0 + 120_000, 7, "Good");

        assertEquals(List.of("-600=5.0", "120=7.0"), rows(raw(-1, 0, "out")));
    }

    @Test
    void theValueBeforeTheStart_keepsItsQuality() throws Exception {
        String u = factry.addMeasurement("default/out", "number");
        factry.add(u, T0 - 700_000, 4, "Good");
        factry.add(u, T0 - 600_000, 5, "Bad");
        factry.add(u, T0 + 30_000, 6, "Good");

        AverageHistoryQuery.Dataset ds = raw(-1, 0, "out");

        assertEquals(List.of("-600=5.0", "30=6.0"), rows(ds));
        assertTrue(ds.qualities.get(0)[1].isBad(), ds::toString);
    }

    @Test
    void returnSize_countsTheValueBeforeTheStartFirst_andTheValueAfterTheEndLast() throws Exception {
        onChangeTag("out");

        assertEquals(List.of("-600=5.0"), rows(raw(1, 0, "out")));
        assertEquals(List.of("-600=5.0", "30=6.0"), rows(raw(2, 0, "out")));
        assertEquals(List.of("-600=5.0", "30=6.0", "120=7.0"), rows(raw(10, 0, "out")));
    }

    @Test
    void severalPens_areBoundedInTwoRequests_inOneTimeOrder() throws Exception {
        onChangeTag("a");
        String b = factry.addMeasurement("default/b", "number");
        factry.add(b, T0 - 300_000, 1, "Good");
        factry.add(b, T0 + 10_000, 2, "Good");
        factry.add(b, T0 + 90_000, 3, "Good");
        String c = factry.addMeasurement("default/c", "number");
        factry.add(c, T0 + 20_000, 9, "Good");

        // The stream the engine hands to Ignition: Ignition's wide writer keeps only the first
        // row past the end, as it does for its own Core Historian.
        List<Long> times = new ArrayList<>();
        engine().query(RawQueryOptions.builder().startTime(T0).endTime(END)
                .addQueryKey(key("a")).addQueryKey(key("b")).addQueryKey(key("c")).build(), new RawPointProcessor() {
                    @Override
                    public boolean onInitialize(ProcessingContext<RawQueryKey, DataPointType> context) {
                        return true;
                    }

                    @Override
                    public boolean onPointAvailable(RawQueryKey key, AtomicPoint<?> point) {
                        times.add((point.timestamp().toEpochMilli() - T0) / 1000);
                        return true;
                    }

                    @Override
                    public void onKeyFailure(RawQueryKey key, QualityCode quality) {
                    }

                    @Override
                    public void onError(QualityCode quality) {
                    }

                    @Override
                    public void onError(Exception e) {
                    }

                    @Override
                    public void onComplete() {
                    }
                });

        assertEquals(List.of(-600L, -300L, 10L, 20L, 30L, 90L, 120L), times);
        // One read of the range and one each for before and after, whatever the number of pens.
        assertEquals(3, factry.queries.size(), () -> factry.queries.toString());
    }

    private static RawQueryKey key(String tag) {
        return new RawQueryKey(QualifiedPath.parseSafe("sys:gw:/prov:default:/tag:" + tag));
    }

    @Test
    void aPenFactryRefuses_isBadFailure_andTheOtherPensKeepTheirBounds() throws Exception {
        onChangeTag("a");
        String b = onChangeTag("b");
        factry.failingMeasurements.add(b);

        AverageHistoryQuery.Dataset ds = raw(-1, 0, "a", "b");

        assertEquals(List.of("-600=5.0", "30=6.0", "120=7.0"), rows(ds));
        for (QualityCode[] q : ds.qualities) {
            assertEquals(QualityCode.Bad_Failure.getCode(), q[2].getCode(), ds::toString);
        }
    }

    @Test
    void whenFactryRefusesTheValuesAroundTheRange_theRangeIsStillReturned() throws Exception {
        onChangeTag("out");
        refuseBounds = true;

        assertEquals(List.of("30=6.0"), rows(raw(-1, 0, "out")));
    }
}
