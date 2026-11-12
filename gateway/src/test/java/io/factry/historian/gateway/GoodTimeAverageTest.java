package io.factry.historian.gateway;

import io.factry.historian.gateway.GoodTimeAverage.BucketStatus;
import io.factry.historian.gateway.GoodTimeAverage.Sample;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The inputs mirror what Factry 8.2.3 returned for the same samples (twa fill "previous"
 * without a status filter, and {@code last} grouped by status).
 */
class GoodTimeAverageTest {

    private static final long MIN = 60_000;

    private static long t(String hhmm) {
        return Instant.parse("2026-11-10T" + hhmm + ":00Z").toEpochMilli();
    }

    /** Raw samples on a fake Factry; records each [from, to) it is asked for. */
    private static final class Raw implements BiFunction<Long, Long, List<Sample>> {
        final List<Sample> all;
        final List<String> calls = new ArrayList<>();

        Raw(Sample... all) {
            this.all = List.of(all);
        }

        @Override
        public List<Sample> apply(Long from, Long to) {
            calls.add(Instant.ofEpochMilli(from) + ".." + Instant.ofEpochMilli(to));
            return all.stream().filter(s -> s.ts() >= from && s.ts() < to).toList();
        }
    }

    private static Sample good(String hhmm, double v) {
        return new Sample(t(hhmm), v, true);
    }

    private static Sample bad(String hhmm) {
        return new Sample(t(hhmm), -1, false);
    }

    private static BucketStatus status(Double lastGood, boolean notGood) {
        BucketStatus st = new BucketStatus();
        if (lastGood != null) {
            st.add(true, lastGood);
        }
        if (notGood) {
            st.add(false, -1);
        }
        return st;
    }

    private static Map<Long, Double> buckets(String... hhmmValue) {
        Map<Long, Double> m = new LinkedHashMap<>();
        for (String kv : hhmmValue) {
            String[] p = kv.split("=");
            m.put(t(p[0]), Double.parseDouble(p[1]));
        }
        return m;
    }

    @Test
    void outage_isAGapAndEdgeBucketsAverageOnlyTheirGoodTime() {
        // 100 since 07:30, 40 at 08:02, Bad from 08:05 (a Bad sample every 10 minutes),
        // Good again with 50 at 08:55 and 80 at 08:57. Factry's twa with a Good filter gives
        // 52, 40, 40, 40, 40, 54: the outage reads as a flat Good 40.
        Raw raw = new Raw(good("08:02", 40), bad("08:05"), bad("08:15"), bad("08:25"),
                bad("08:35"), bad("08:45"), good("08:55", 50), good("08:57", 80));
        Map<Long, BucketStatus> statuses = Map.of(
                t("08:00"), status(40.0, true),
                t("08:10"), status(null, true),
                t("08:20"), status(null, true),
                t("08:30"), status(null, true),
                t("08:40"), status(null, true),
                t("08:50"), status(80.0, false));

        Map<Long, Double> out = GoodTimeAverage.compute(
                buckets("08:00=31.5", "08:10=-1", "08:20=-1", "08:30=-1", "08:40=-1", "08:50=33.5"),
                statuses, good("07:30", 100), 10 * MIN, t("09:00"), raw);

        // 08:00: 2 min at 100 and 3 min at 40 over 5 Good minutes. 08:50: 2 min at 50 and
        // 3 min at 80 over 5 Good minutes. The four buckets inside the outage are left out.
        assertEquals(Map.of(t("08:00"), 64.0, t("08:50"), 68.0), out);
        // Only the two buckets where the status changes are fetched raw, not the outage.
        assertEquals(List.of("2026-11-10T08:00:00Z..2026-11-10T08:10:00Z",
                "2026-11-10T08:50:00Z..2026-11-10T09:00:00Z"), raw.calls);
    }

    @Test
    void outage_inOneBucket_averagesOnlyItsGoodTime() {
        Raw raw = new Raw(good("08:02", 40), bad("08:05"), bad("08:15"), bad("08:25"),
                bad("08:35"), bad("08:45"), good("08:55", 50), good("08:57", 80));
        Map<Long, BucketStatus> statuses = Map.of(t("08:00"), status(80.0, true));

        Map<Long, Double> out = GoodTimeAverage.compute(buckets("08:00=31.5"), statuses,
                good("07:30", 100), 60 * MIN, t("09:00"), raw);

        // (2*100 + 3*40 + 2*50 + 3*80) / 10 Good minutes, not 31.5 (Bad values in) or 52 (held).
        assertEquals(Map.of(t("08:00"), 66.0), out);
    }

    @Test
    void goodThroughout_usesFactryTwaWithoutRawSamples() {
        Raw raw = new Raw();
        Map<Long, BucketStatus> statuses = Map.of(t("08:00"), status(0.0, false));

        Map<Long, Double> out = GoodTimeAverage.compute(buckets("08:00=10", "08:10=0", "08:20=0"),
                statuses, good("07:00", 100), 10 * MIN, t("08:30"), raw);

        assertEquals(buckets("08:00=10", "08:10=0", "08:20=0"), out);
        assertEquals(List.of(), raw.calls);
    }

    @Test
    void rangeStartingInsideAnOutage_isAGapUntilGoodReturns() {
        Raw raw = new Raw(good("08:25", 20));
        Map<Long, BucketStatus> statuses = Map.of(t("08:20"), status(20.0, false));

        Map<Long, Double> out = GoodTimeAverage.compute(buckets("08:00=-1", "08:10=-1", "08:20=14", "08:30=20"),
                statuses, bad("07:50"), 10 * MIN, t("08:40"), raw);

        // 08:20 holds Good only from 08:25; 08:30 is Good throughout.
        assertEquals(buckets("08:20=20", "08:30=20"), out);
        assertEquals(List.of("2026-11-10T08:20:00Z..2026-11-10T08:30:00Z"), raw.calls);
    }

    @Test
    void lastSampleBad_leavesTheRestOfTheRangeEmpty() {
        Raw raw = new Raw(bad("08:05"));
        Map<Long, BucketStatus> statuses = Map.of(t("08:00"), status(null, true));

        Map<Long, Double> out = GoodTimeAverage.compute(buckets("08:00=50", "08:10=-1", "08:20=-1"),
                statuses, good("07:00", 100), 10 * MIN, t("08:30"), raw);

        assertEquals(buckets("08:00=100"), out);
    }

    @Test
    void uncertainSamples_areNotGoodTime() {
        // statuses are classified by the engine; an Uncertain sample is passed as not good.
        Raw raw = new Raw(new Sample(t("08:05"), 7, false), good("08:08", 10));
        Map<Long, BucketStatus> statuses = Map.of(t("08:00"), status(10.0, true));

        Map<Long, Double> out = GoodTimeAverage.compute(buckets("08:00=99"), statuses,
                good("07:00", 4), 10 * MIN, t("08:10"), raw);

        assertEquals(Map.of(t("08:00"), (5 * 4 + 2 * 10) / 7.0), out);
    }

    @Test
    void twoGoodStatusesInABucket_areFetchedRaw() {
        // "Good" and "GoodLocalOverride" are both Good, but per-status last values cannot say
        // which came last, so the bucket is computed from its samples.
        Raw raw = new Raw(good("08:00", 10), good("08:30", 20));
        BucketStatus st = status(10.0, false);
        st.add(true, 20);

        Map<Long, Double> out = GoodTimeAverage.compute(buckets("08:00=15"), Map.of(t("08:00"), st),
                null, 60 * MIN, t("09:00"), raw);

        assertEquals(Map.of(t("08:00"), 15.0), out);
        assertEquals(1, raw.calls.size());
    }
}
