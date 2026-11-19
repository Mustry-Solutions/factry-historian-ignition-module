package io.factry.historian.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
 *       over the Good time only.
 * </ul>
 *
 * <p>Raw reads are bounded by a {@link Budget}. Neighbouring buckets that need raw samples share
 * one request, also across buckets in between that do not need them, as long as the request
 * stays within the budget. A bucket the budget does not cover gets the {@link Source#fallback()}
 * value instead, marked as not exact: a slow or partly wrong number for one bucket is better
 * than a pen that fails because one request grew past the gRPC message limit.
 *
 * <p>Free of Ignition SDK and gRPC types so it can be unit-tested without Factry.
 */
final class GoodTimeAverage {
    private GoodTimeAverage() {}

    /** One stored sample; {@code good} is its status level. A non-numeric value is NaN. */
    record Sample(long ts, double value, boolean good) {}

    /** One bucket's average. {@code exact} is false for a fallback value (see the class doc). */
    record Result(double value, boolean exact) {}

    /** Limits on raw reads for one pen. */
    record Budget(long pointsPerRequest, long pointsPerPen, int requestsPerPen) {}

    /** What Factry is asked for beyond the aggregates passed in. Each call is one request. */
    interface Source {
        /** Number of samples (all statuses) per bucket start; buckets without samples absent. */
        Map<Long, Long> counts();

        /** The raw samples in [from, to), in time order. */
        List<Sample> raw(long from, long to);

        /** A value per bucket start for buckets the budget cannot read raw. */
        Map<Long, Double> fallback();
    }

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

        boolean isEmpty() {
            return !hasGood && !hasNotGood;
        }

        /** Holds more than one status, so the order of its samples is unknown without raw. */
        boolean isMixed() {
            return (hasGood && hasNotGood) || goodSeries > 1;
        }
    }

    private static final BucketStatus EMPTY = new BucketStatus();

    /** The status in force at a bucket start, as far as the plan can tell before reading raw. */
    private enum Held {
        /** No sample yet. */
        NONE, GOOD, NOT_GOOD,
        /** Known only once a raw request that is in the plan has been read. */
        READ,
        /** Unknown: the bucket that set it was not read raw. */
        LOST
    }

    /** Buckets {@code first..last} (indexes) read in one raw request. */
    private record Span(int first, int last) {}

    /**
     * @param twa        Factry's twa per bucket start, in time order (fill "previous", no filter)
     * @param statuses   per bucket start, which statuses it holds (buckets without samples absent)
     * @param seed       the last sample before the range, or null when there is none
     * @param periodMs   bucket length
     * @param rangeEndMs the query end; the last bucket stops here
     * @return the value per bucket start, only for buckets with Good time
     */
    static Map<Long, Result> compute(
            Map<Long, Double> twa, Map<Long, BucketStatus> statuses, Sample seed,
            long periodMs, long rangeEndMs, Budget budget, Source source) {

        List<Long> buckets = new ArrayList<>(twa.keySet());
        List<Span> spans = plan(buckets, statuses, seed, budget, source);

        Map<Long, Result> out = new LinkedHashMap<>();
        Map<Long, Double> fallback = null;
        Sample held = seed;
        boolean lost = false;
        int nextSpan = 0;

        int i = 0;
        while (i < buckets.size()) {
            if (nextSpan < spans.size() && spans.get(nextSpan).first() == i) {
                Span span = spans.get(nextSpan++);
                held = averageRaw(buckets, span, held, periodMs, rangeEndMs, source, out);
                lost = false;
                i = span.last() + 1;
                continue;
            }

            long start = buckets.get(i);
            BucketStatus st = statuses.getOrDefault(start, EMPTY);
            boolean heldGood = held != null && held.good();
            boolean exact = !lost && !st.isMixed()
                    && !(heldGood && st.hasNotGood) && !(held != null && !heldGood && st.hasGood);
            if (exact) {
                if (heldGood || st.hasGood) {
                    out.put(start, new Result(twa.get(start), true));
                }
                // else: not Good the whole bucket, a gap.
            } else {
                if (fallback == null) {
                    fallback = source.fallback();
                }
                Double v = fallback.get(start);
                if (v != null && !Double.isNaN(v)) {
                    out.put(start, new Result(v, false));
                }
            }

            if (st.isMixed()) {
                lost = true;
            } else if (st.hasGood) {
                held = new Sample(start, st.lastGood, true);
                lost = false;
            } else if (st.hasNotGood) {
                held = new Sample(start, Double.NaN, false);
                lost = false;
            }
            i++;
        }
        return out;
    }

    /**
     * Picks the raw requests: one pass that predicts which buckets need raw samples and groups
     * them under the budget. A request never starts where the held status is lost, because its
     * first bucket could then not be averaged.
     */
    private static List<Span> plan(List<Long> buckets, Map<Long, BucketStatus> statuses, Sample seed,
                                   Budget budget, Source source) {
        List<Span> spans = new ArrayList<>();
        long[] before = null; // before[i] = samples in buckets 0..i-1
        long penPoints = 0;
        int first = -1;
        int last = -1;
        Held held = seed == null ? Held.NONE : seed.good() ? Held.GOOD : Held.NOT_GOOD;

        for (int i = 0; i < buckets.size(); i++) {
            BucketStatus st = statuses.getOrDefault(buckets.get(i), EMPTY);
            boolean inSpan = false;
            if (needsRaw(held, st)) {
                if (before == null) {
                    before = prefixCounts(buckets, source.counts());
                }
                if (first >= 0 && before[i + 1] - before[first] <= budget.pointsPerRequest()
                        && penPoints + before[i + 1] - before[last + 1] <= budget.pointsPerPen()) {
                    // Extend the open request; the buckets in between come along.
                    penPoints += before[i + 1] - before[last + 1];
                    last = i;
                    inSpan = true;
                } else {
                    if (first >= 0) {
                        spans.add(new Span(first, last));
                        first = -1;
                    }
                    long own = before[i + 1] - before[i];
                    if (held != Held.LOST && own <= budget.pointsPerRequest()
                            && penPoints + own <= budget.pointsPerPen()
                            && spans.size() < budget.requestsPerPen()) {
                        first = i;
                        last = i;
                        penPoints += own;
                        inSpan = true;
                    }
                }
            }
            if (st.isMixed()) {
                held = inSpan ? Held.READ : Held.LOST;
            } else if (st.hasGood) {
                held = Held.GOOD;
            } else if (st.hasNotGood) {
                held = Held.NOT_GOOD;
            }
        }
        if (first >= 0) {
            spans.add(new Span(first, last));
        }
        return spans;
    }

    /** Whether the bucket's status may change inside it, so twa alone cannot tell its Good time. */
    private static boolean needsRaw(Held held, BucketStatus st) {
        if (st.isMixed()) {
            return true;
        }
        switch (held) {
            case LOST:
                return true;
            case READ:
                return !st.isEmpty();
            case GOOD:
                return st.hasNotGood;
            case NOT_GOOD:
                return st.hasGood;
            default:
                return false;
        }
    }

    private static long[] prefixCounts(List<Long> buckets, Map<Long, Long> counts) {
        long[] before = new long[buckets.size() + 1];
        for (int i = 0; i < buckets.size(); i++) {
            before[i + 1] = before[i] + counts.getOrDefault(buckets.get(i), 0L);
        }
        return before;
    }

    /** Averages the buckets of one span over their Good time; returns the sample held after it. */
    private static Sample averageRaw(List<Long> buckets, Span span, Sample held, long periodMs,
                                     long rangeEndMs, Source source, Map<Long, Result> out) {
        List<Sample> samples = source.raw(buckets.get(span.first()),
                bucketEnd(buckets.get(span.last()), periodMs, rangeEndMs));
        int s = 0;
        for (int b = span.first(); b <= span.last(); b++) {
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
                out.put(from, new Result(sum / goodMs, true));
            }
        }
        return held;
    }

    private static boolean isGoodNumber(Sample s) {
        return s != null && s.good() && !Double.isNaN(s.value());
    }

    private static long bucketEnd(long start, long periodMs, long rangeEndMs) {
        return Math.min(start + periodMs, rangeEndMs);
    }
}
