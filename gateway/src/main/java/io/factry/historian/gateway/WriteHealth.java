package io.factry.historian.gateway;

import com.inductiveautomation.ignition.common.i18n.LocalizedString;
import com.inductiveautomation.ignition.gateway.model.ProfileStatus;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;

/**
 * What the profile status says, from the connection check and from what becomes of the writes.
 * The connection check (GetCollector) passes while Factry stores nothing: it refuses every
 * CreatePoints (INTERNAL while its InfluxDB is down) or lets them all time out (InfluxDB
 * hanging). Store and forward keeps that data and retries it, but the profile would show
 * Running. So writes that all failed over at least {@link #FAILING_AFTER_MS} turn the status
 * Errored, until one is stored again. A failed check comes first: it says Factry is unreachable.
 * <p>
 * Also logs the failed writes: one WARN per new answer from Factry, a summary about every
 * {@link #SUMMARY_EVERY_MS} while it lasts, and the totals when writes are stored again.
 */
final class WriteHealth {
    private static final Logger logger = LoggerFactory.getLogger(WriteHealth.class);

    /** One connection check interval: a single failed flush is not a reason to alarm anyone. */
    static final long FAILING_AFTER_MS = 30_000;
    static final long SUMMARY_EVERY_MS = 5 * 60_000;

    private final String prefix;
    private final LongSupplier clock;

    /** Null until the first connection check. */
    private Boolean connected;
    /** When the first write failed after the last stored one; null while writes are stored. */
    private Long failingSince;
    private long lastFailedAt;
    private Status lastFailure;
    /** The answer last logged at WARN, digits left out (a deadline names its exact time). */
    private String lastLoggedAnswer;
    /** Whether the switch to failing was logged; then when the last summary was. */
    private boolean failingLogged;
    private long summaryAt;
    private long failedFlushes, failedPoints, windowFlushes, windowPoints;

    WriteHealth() {
        this("Factry Historian", System::currentTimeMillis);
    }

    WriteHealth(String historianName) {
        this(historianName, System::currentTimeMillis);
    }

    WriteHealth(String historianName, LongSupplier clock) {
        this.prefix = "Historian '" + historianName + "': ";
        this.clock = clock;
    }

    /** A failed check forgets the writes: after the outage they are judged afresh. */
    synchronized void connectionChecked(boolean ok) {
        connected = ok;
        if (!ok) {
            clearWrites();
            return;
        }
        if (failingLogged && clock.getAsLong() - summaryAt >= SUMMARY_EVERY_MS) {
            summaryAt = clock.getAsLong();
            logger.warn(prefix + "Factry still does not store writes: " + windowFlushes + " flushes / "
                    + windowPoints + " points in the last 5 min (last answer: " + describe(lastFailure) + ")");
            windowFlushes = 0;
            windowPoints = 0;
        }
    }

    /** Factry answered OK to a CreatePoints. */
    synchronized void writeAccepted() {
        if (failingLogged) {
            logger.info(prefix + "Factry stores writes again, after " + failedFlushes + " failed flushes / "
                    + failedPoints + " points");
        }
        clearWrites();
    }

    /** A flush of {@code points} points was not stored: Factry refused it, or did not answer in time. */
    synchronized void writeFailed(Status status, int points) {
        long now = clock.getAsLong();
        if (failingSince == null) {
            failingSince = now;
        }
        lastFailedAt = now;
        lastFailure = status;
        failedFlushes++;
        failedPoints += points;
        windowFlushes++;
        windowPoints += points;

        String answer = status.getCode() + ":" + String.valueOf(status.getDescription()).replaceAll("[0-9.]+", "#");
        String line = prefix + "Factry did not store " + points + " points (" + describe(status)
                + "); store and forward keeps them and retries";
        if (!answer.equals(lastLoggedAnswer)) {
            lastLoggedAnswer = answer;
            logger.warn(line + ". The same answer again is logged at debug level.");
        } else {
            logger.debug(line);
        }

        if (!failingLogged && failing()) {
            failingLogged = true;
            summaryAt = now;
            windowFlushes = 0;
            windowPoints = 0;
            logger.warn(prefix + failingDetails() + " The profile shows Errored until a write is stored.");
        }
    }

    /** Forget the writes, for new settings or after an outage: they are judged afresh. */
    synchronized void clearWrites() {
        failingSince = null;
        lastFailure = null;
        lastLoggedAnswer = null;
        failingLogged = false;
        failedFlushes = 0;
        failedPoints = 0;
        windowFlushes = 0;
        windowPoints = 0;
    }

    synchronized ProfileStatus status() {
        if (connected == null) {
            return ProfileStatus.UNKNOWN;
        }
        if (!connected) {
            return ProfileStatus.ERRORED;
        }
        if (failing()) {
            // The gateway's historian page shows an Errored profile as "Error" with the details as
            // its tooltip, so the details carry the whole story.
            return new ProfileStatus(ProfileStatus.State.Errored,
                    LocalizedString.createRaw("Factry does not store writes"), failingDetails());
        }
        return ProfileStatus.RUNNING;
    }

    /** Failed writes spread over the interval, not one failure and then silence. */
    private boolean failing() {
        return failingSince != null && lastFailedAt - failingSince >= FAILING_AFTER_MS;
    }

    private String failingDetails() {
        return "No write to Factry has succeeded for " + duration(lastFailedAt - failingSince)
                + " (last answer: " + describe(lastFailure) + "). Store and forward keeps the data and"
                + " retries it.";
    }

    static String describe(Status status) {
        String description = status.getDescription();
        return description == null || description.isEmpty()
                ? status.getCode().toString() : status.getCode() + ": " + description;
    }

    private static String duration(long millis) {
        long seconds = millis / 1000;
        return seconds < 120 ? seconds + " s" : seconds / 60 + " min";
    }
}
