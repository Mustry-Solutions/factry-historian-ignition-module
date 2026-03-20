package io.factry.historian.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple metrics tracker for the Factry Historian module.
 *
 * Tracks counters and cumulative timings for storage and query operations.
 * Periodically logs a summary for benchmarking and production monitoring.
 */
public class HistorianMetrics {

    private static final Logger logger = LoggerFactory.getLogger(HistorianMetrics.class);

    // Store metrics
    private final AtomicLong storeCount = new AtomicLong();
    private final AtomicLong storePointCount = new AtomicLong();
    private final AtomicLong storeTotalMs = new AtomicLong();
    private final AtomicLong storeErrorCount = new AtomicLong();

    // Raw query metrics
    private final AtomicLong rawQueryCount = new AtomicLong();
    private final AtomicLong rawQueryRowCount = new AtomicLong();
    private final AtomicLong rawQueryTotalMs = new AtomicLong();

    // Aggregated query metrics
    private final AtomicLong aggQueryCount = new AtomicLong();
    private final AtomicLong aggQueryRowCount = new AtomicLong();
    private final AtomicLong aggQueryTotalMs = new AtomicLong();

    // Track last log time for computing rates
    private volatile long lastLogTimeMs = System.currentTimeMillis();
    private volatile long lastStorePointCount = 0;
    private volatile long lastStoreCount = 0;

    public void recordStore(int points, long elapsedMs) {
        storeCount.incrementAndGet();
        storePointCount.addAndGet(points);
        storeTotalMs.addAndGet(elapsedMs);
    }

    public void recordStoreError() {
        storeErrorCount.incrementAndGet();
    }

    public void recordRawQuery(int rows, long elapsedMs) {
        rawQueryCount.incrementAndGet();
        rawQueryRowCount.addAndGet(rows);
        rawQueryTotalMs.addAndGet(elapsedMs);
    }

    public void recordAggregatedQuery(int rows, long elapsedMs) {
        aggQueryCount.incrementAndGet();
        aggQueryRowCount.addAndGet(rows);
        aggQueryTotalMs.addAndGet(elapsedMs);
    }

    public void logSummary() {
        long now = System.currentTimeMillis();
        long intervalMs = now - lastLogTimeMs;
        if (intervalMs <= 0) {
            intervalMs = 1;
        }
        lastLogTimeMs = now;

        long currentStorePoints = storePointCount.get();
        long currentStoreCount = storeCount.get();
        long pointsDelta = currentStorePoints - lastStorePointCount;
        long storesDelta = currentStoreCount - lastStoreCount;
        lastStorePointCount = currentStorePoints;
        lastStoreCount = currentStoreCount;

        double pointsPerSec = (pointsDelta * 1000.0) / intervalMs;

        long errors = storeErrorCount.get();
        long rawQueries = rawQueryCount.get();
        long aggQueries = aggQueryCount.get();

        logger.info(String.format(
                "Metrics | store: %d ops, %d pts (%.1f pts/s), %d ms total, %d errors | " +
                "raw query: %d ops, %d rows, %d ms total | " +
                "agg query: %d ops, %d rows, %d ms total",
                storesDelta, pointsDelta, pointsPerSec, storeTotalMs.get(), errors,
                rawQueries, rawQueryRowCount.get(), rawQueryTotalMs.get(),
                aggQueries, aggQueryRowCount.get(), aggQueryTotalMs.get()
        ));
    }

    public void reset() {
        storeCount.set(0);
        storePointCount.set(0);
        storeTotalMs.set(0);
        storeErrorCount.set(0);
        rawQueryCount.set(0);
        rawQueryRowCount.set(0);
        rawQueryTotalMs.set(0);
        aggQueryCount.set(0);
        aggQueryRowCount.set(0);
        aggQueryTotalMs.set(0);
        lastStorePointCount = 0;
        lastStoreCount = 0;
        lastLogTimeMs = System.currentTimeMillis();
    }

    public long getStoreCount() { return storeCount.get(); }
    public long getStorePointCount() { return storePointCount.get(); }
    public long getStoreTotalMs() { return storeTotalMs.get(); }
    public long getStoreErrorCount() { return storeErrorCount.get(); }
    public long getRawQueryCount() { return rawQueryCount.get(); }
    public long getRawQueryRowCount() { return rawQueryRowCount.get(); }
    public long getRawQueryTotalMs() { return rawQueryTotalMs.get(); }
    public long getAggQueryCount() { return aggQueryCount.get(); }
    public long getAggQueryRowCount() { return aggQueryRowCount.get(); }
    public long getAggQueryTotalMs() { return aggQueryTotalMs.get(); }
}
