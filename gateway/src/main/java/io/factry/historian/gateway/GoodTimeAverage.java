package io.factry.historian.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Ignition's time-weighted "Average", counted over Good time only.
 *
 * <p>Each stored sample holds until the next one, whatever its status. A Bad or Uncertain sample
 * therefore ends the Good time of the value before it. Factry's {@code twa} (fill "previous")
 * cannot do this: it refuses group by, and a status filter makes it hold the last Good value
 * straight through an outage stored as Bad samples.
 *
 * <p>So per bucket this class decides from cheap aggregates which case applies:
 * <ul>
 *   <li>Good the whole bucket (held Good, no other status inside): Factry's {@code twa} is right.
 *   <li>Not Good the whole bucket (held not Good, no Good sample inside): no value, a gap. This
 *       matches Ignition's own TimeWeightedAvgAggregate, which returns no data for a block
 *       without a Good value.
 *   <li>Otherwise the status changes inside the bucket: its raw samples are fetched and averaged
 *       over the Good time only. Only these buckets cost raw samples, so a long trend does not
 *       pull every sample of the range.
 * </ul>
 *
 * <p>Free of Ignition SDK and gRPC types so it can be unit-tested without Factry.
 */
final class GoodTimeAverage {
    private GoodTimeAverage() {}

    /** One stored sample; {@code good} is its status level. A non-numeric value is NaN. */
    record Sample(long ts, double value, boolean good) {}

    /** What one bucket holds, from Factry's {@code last} aggregate grouped by status. */
    static final class BucketStatus {
        boolean hasGood;
        boolean hasNotGood;
        /** Number of Good-level status series ("Good", "GoodLocalOverride", ...) in the bucket. */
        int goodSeries;
        /** The last Good value; only meaningful when exactly one Good series is present. */
        double lastGood = Double.NaN;

        void add(boolean good, double last) {
            if (good) {
                hasGood = true;
                goodSeries++;
                lastGood = last;
            } else {
                hasNotGood = true;
            }
        }
    }

    private static final BucketStatus EMPTY = new BucketStatus();

    /**
     * @param twa        Factry's twa per bucket start, in time order (fill "previous", no filter)
     * @param statuses   per bucket start, which statuses it holds (buckets without samples absent)
     * @param seed       the last sample before the range, or null when there is none
     * @param periodMs   bucket length
     * @param rangeEndMs the query end; the last bucket stops here
     * @param raw        fetches the raw samples in [from, to), in time order
     * @return the value per bucket start, only for buckets with Good time
     */
    static Map<Long, Double> compute(
            Map<Long, Double> twa, Map<Long, BucketStatus> statuses, Sample seed,
            long periodMs, long rangeEndMs, BiFunction<Long, Long, List<Sample>> raw) {

        List<Long> buckets = new ArrayList<>(twa.keySet());
        Map<Long, Double> out = new LinkedHashMap<>();
        Sample held = seed;

        int i = 0;
        while (i < buckets.size()) {
            long start = buckets.get(i);
            BucketStatus st = statuses.getOrDefault(start, EMPTY);
            boolean heldNotGood = held != null && !held.good();

            if (!st.hasNotGood && !heldNotGood && st.goodSeries <= 1) {
                out.put(start, twa.get(start));
                if (st.hasGood) {
                    held = new Sample(start, st.lastGood, true);
                }
                i++;
                continue;
            }
            if (!st.hasGood && (heldNotGood || held == null)) {
                if (st.hasNotGood) {
                    held = new Sample(start, Double.NaN, false);
                }
                i++;
                continue;
            }

            // The status changes inside this bucket. Fetch it raw, in one request together with
            // the buckets right after it that hold both Good and not-Good samples (those need
            // raw samples whatever status they start in). A bucket of only Bad samples is not
            // fetched: it is a gap or, when it starts Good, its own short raw request.
            int last = i;
            while (last + 1 < buckets.size() && mixed(statuses.getOrDefault(buckets.get(last + 1), EMPTY))) {
                last++;
            }
            List<Sample> samples = raw.apply(start, bucketEnd(buckets.get(last), periodMs, rangeEndMs));
            int s = 0;
            for (int b = i; b <= last; b++) {
                long from = buckets.get(b);
                long to = bucketEnd(from, periodMs, rangeEndMs);
                double sum = 0;
                long goodMs = 0;
                long t = from;
                while (s < samples.size() && samples.get(s).ts() < to) {
                    Sample next = samples.get(s++);
                    if (next.ts() < from) {
                        held = next;
                        continue;
                    }
                    if (isGoodNumber(held)) {
                        sum += held.value() * (next.ts() - t);
                        goodMs += next.ts() - t;
                    }
                    held = next;
                    t = next.ts();
                }
                if (isGoodNumber(held)) {
                    sum += held.value() * (to - t);
                    goodMs += to - t;
                }
                if (goodMs > 0) {
                    out.put(from, sum / goodMs);
                }
            }
            i = last + 1;
        }
        return out;
    }

    private static boolean mixed(BucketStatus st) {
        return st.hasGood && st.hasNotGood;
    }

    private static boolean isGoodNumber(Sample s) {
        return s != null && s.good() && !Double.isNaN(s.value());
    }

    private static long bucketEnd(long start, long periodMs, long rangeEndMs) {
        return Math.min(start + periodMs, rangeEndMs);
    }
}
