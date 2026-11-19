package io.factry.historian.gateway;

import com.inductiveautomation.historian.common.model.LegacyAggregateAdapter;
import com.inductiveautomation.historian.common.model.TimeWindow;
import com.inductiveautomation.historian.common.model.data.AggregatedPoint;
import com.inductiveautomation.historian.common.model.data.DataPointType;
import com.inductiveautomation.historian.common.model.options.AggregatedQueryKey;
import com.inductiveautomation.historian.common.model.options.AggregatedQueryOptions;
import com.inductiveautomation.historian.gateway.api.query.processor.AggregatedPointProcessor;
import com.inductiveautomation.historian.gateway.api.query.processor.ProcessingContext;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.history.AggregationMode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Ignition's "Average" through the engine's public query entry point, against a fake Factry. */
class FactryQueryEngineAverageTest {

    private static final long MIN = 60_000;

    private final TwaFakeFactry factry = new TwaFakeFactry();

    private static long t(String hhmm) {
        return Instant.parse("2026-11-10T" + hhmm + ":00Z").toEpochMilli();
    }

    private FactryQueryEngine engine() {
        FactryHistorianSettings settings = new FactryHistorianSettings();
        settings.setDelimiter("/");
        MeasurementCache cache = new MeasurementCache();
        cache.refresh(factry);
        return new FactryQueryEngine(TwaFakeFactry.gatewayContext(), "Factry", settings, factry, cache,
                new HistorianMetrics());
    }

    private static AggregatedQueryKey key(String tag, AggregationMode mode) {
        return AggregatedQueryKey.of(QualifiedPath.parseSafe("sys:gw:/prov:default:/tag:" + tag),
                LegacyAggregateAdapter.of(mode));
    }

    /** Records what the engine hands to Ignition. */
    static final class Recorder implements AggregatedPointProcessor {
        final List<AggregatedPoint<?, ?>> points = new ArrayList<>();
        final List<QualityCode> keyFailures = new ArrayList<>();
        Exception error;

        @Override
        public boolean onInitialize(ProcessingContext<AggregatedQueryKey, DataPointType> context) {
            return true;
        }

        @Override
        public boolean onPointAvailable(AggregatedQueryKey key, AggregatedPoint<?, ?> point) {
            points.add(point);
            return true;
        }

        @Override
        public void onKeyFailure(AggregatedQueryKey key, QualityCode quality) {
            keyFailures.add(quality);
        }

        @Override
        public void onError(QualityCode quality) {
            error = new RuntimeException(String.valueOf(quality));
        }

        @Override
        public void onError(Exception e) {
            error = e;
        }

        @Override
        public void onComplete() {
        }

        /** Value per bucket start, in order. */
        Map<Long, Double> values() {
            Map<Long, Double> out = new LinkedHashMap<>();
            for (AggregatedPoint<?, ?> p : points) {
                out.put(p.timestamp().toEpochMilli(), ((Number) p.value()).doubleValue());
            }
            return out;
        }

        Map<Long, QualityCode> qualities() {
            Map<Long, QualityCode> out = new LinkedHashMap<>();
            for (AggregatedPoint<?, ?> p : points) {
                out.put(p.timestamp().toEpochMilli(), p.quality());
            }
            return out;
        }
    }

    private Recorder average(FactryQueryEngine engine, String tag, long start, long end, TimeWindow window) {
        Recorder rec = new Recorder();
        engine.query(AggregatedQueryOptions.builder()
                .startTime(start).endTime(end)
                .withTimeWindow(window)
                .addQueryKey(key(tag, AggregationMode.Average))
                .build(), rec);
        assertNull(rec.error, () -> "query failed: " + rec.error);
        assertEquals(List.of(), rec.keyFailures);
        return rec;
    }

    private static void assertValues(Map<Long, Double> expected, Map<Long, Double> actual) {
        assertEquals(expected.keySet(), actual.keySet(), "bucket starts");
        for (Map.Entry<Long, Double> e : expected.entrySet()) {
            assertEquals(e.getValue(), actual.get(e.getKey()), 1e-9, "bucket " + Instant.ofEpochMilli(e.getKey()));
        }
    }

    @Test
    void valueFromBeforeTheRangeSeedsTheAverage_andAnOutageIsAGap() {
        String u = factry.addMeasurement("default/a", "number");
        factry.add(u, t("07:30"), 100, "Good");
        factry.add(u, t("08:02"), 40, "Good");
        for (String bad : List.of("08:05", "08:15", "08:25", "08:35", "08:45")) {
            factry.add(u, t(bad), 1000, "Bad");
        }
        factry.add(u, t("08:55"), 50, "Good");
        factry.add(u, t("08:57"), 80, "Good");

        Recorder rec = average(engine(), "a", t("08:00"), t("09:00"), TimeWindow.ofMinutes(10));

        // 08:00: 2 min at 100 (held from 07:30) and 3 min at 40 over 5 Good minutes.
        // 08:50: 2 min at 50 and 3 min at 80. The buckets inside the outage are gaps.
        assertValues(Map.of(t("08:00"), 64.0, t("08:50"), 68.0), rec.values());
        assertTrue(rec.qualities().values().stream().allMatch(QualityCode::isGood));
    }

    @Test
    void goodSubStatusIsGood_uncertainIsNot() {
        String u = factry.addMeasurement("default/a", "number");
        factry.add(u, t("07:00"), 4, "Good");
        factry.add(u, t("08:05"), 7, "UncertainInitialValue");
        factry.add(u, t("08:08"), 10, "GoodLocalOverride");

        Recorder rec = average(engine(), "a", t("08:00"), t("08:10"), TimeWindow.ofMinutes(10));

        // 5 min at 4, the Uncertain 3 minutes left out, 2 min at 10.
        assertValues(Map.of(t("08:00"), (5 * 4 + 2 * 10) / 7.0), rec.values());
    }

    @Test
    void lastBucketStopsAtTheRangeEnd() {
        String u = factry.addMeasurement("default/a", "number");
        factry.add(u, t("07:00"), 100, "Good");
        factry.add(u, t("08:21"), 50, "Good");
        factry.add(u, t("08:23"), 0, "Bad");

        Recorder rec = average(engine(), "a", t("08:00"), t("08:25"), TimeWindow.ofMinutes(10));

        // The last bucket is 08:20 to 08:25: Good from 08:20 to 08:23 only.
        assertValues(Map.of(t("08:00"), 100.0, t("08:10"), 100.0, t("08:20"), (100 + 2 * 50) / 3.0),
                rec.values());
    }
}
