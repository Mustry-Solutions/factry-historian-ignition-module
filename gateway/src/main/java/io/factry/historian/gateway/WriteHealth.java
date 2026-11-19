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
 * Errored, until one is stored again. A failed check comes first and says why: unreachable, a
 * refused token or a deleted collector.
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

    /** Factry's answer to the last connection check; null until the first one. */
    private Status check;
    /** Why the last settings save was not applied; null once the check runs again. */
    private String settingsRejection;
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

    /**
     * Record the connection check. Logs one WARN when the reason it fails changes, rather than
     * a line per check, and one INFO when it passes again. A failed check forgets the writes:
     * after the outage they are judged afresh.
     */
    synchronized void connectionChecked(Status status) {
        Status previous = check;
        check = status;
        settingsRejection = null;
        if (!status.isOk()) {
            if (previous == null || previous.isOk()
                    || checkFailure(previous) != checkFailure(status) || previous.getCode() != status.getCode()) {
                logger.warn(prefix + checkFailure(status).details(status));
            }
            clearWrites();
            return;
        }
        if (previous != null && !previous.isOk()) {
            logger.info(prefix + "Factry Historian answers the connection check again");
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

    /**
     * New settings could not be applied (Ignition then restarts the profile). The caller logs the
     * error; the status says it until the next connection check.
     */
    synchronized void settingsRejected(Throwable e) {
        StringBuilder reason = new StringBuilder();
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && reason.indexOf(t.getMessage()) < 0) {
                reason.append(reason.length() == 0 ? "" : ": ").append(t.getMessage());
            }
        }
        settingsRejection = reason.length() == 0 ? e.getClass().getSimpleName() : reason.toString();
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
        if (settingsRejection != null) {
            return new ProfileStatus(ProfileStatus.State.Errored, LocalizedString.createRaw("Settings rejected"),
                    "Settings rejected: " + settingsRejection);
        }
        if (check == null) {
            return ProfileStatus.UNKNOWN;
        }
        if (!check.isOk()) {
            CheckFailure failure = checkFailure(check);
            return new ProfileStatus(ProfileStatus.State.Errored, LocalizedString.createRaw(failure.message),
                    failure.details(check));
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

    /** Why the connection check fails, in words an operator can act on. */
    enum CheckFailure {
        UNREACHABLE("Factry Historian unreachable", "Cannot reach Factry Historian (%s)."),
        TOKEN_REFUSED("Factry refused the collector token",
                "Factry refused the collector token (%s). Replace the token in the profile settings."),
        COLLECTOR_DELETED("Collector deleted in Factry",
                "The collector of this token was deleted in Factry (%s). Create a collector in Factry"
                        + " and paste its token in the profile settings."),
        OTHER("Factry Historian check failed", "The connection check to Factry Historian failed (%s).");

        final String message;
        private final String details;

        CheckFailure(String message, String details) {
            this.message = message;
            this.details = details;
        }

        String details(Status status) {
            return String.format(details, describe(status)) + " Store and forward keeps the data until it can"
                    + " be stored.";
        }
    }

    static CheckFailure checkFailure(Status status) {
        switch (status.getCode()) {
            case UNAVAILABLE:
            case DEADLINE_EXCEEDED:
                return CheckFailure.UNREACHABLE;
            case UNAUTHENTICATED:
            case PERMISSION_DENIED:
                return CheckFailure.TOKEN_REFUSED;
            default:
                return FactryGrpcClient.isCollectorDeleted(status) ? CheckFailure.COLLECTOR_DELETED : CheckFailure.OTHER;
        }
    }

    /** Code, Factry's text and the underlying cause (an SSL or socket error says most). */
    static String describe(Status status) {
        StringBuilder sb = new StringBuilder(status.getCode().toString());
        String description = status.getDescription();
        if (description != null && !description.isEmpty()) {
            sb.append(": ").append(description);
        }
        Throwable cause = status.getCause();
        if (cause != null && cause.getMessage() != null
                && (description == null || !description.contains(cause.getMessage()))) {
            sb.append(": ").append(cause.getMessage());
        }
        return sb.toString();
    }

    private static String duration(long millis) {
        long seconds = millis / 1000;
        return seconds < 120 ? seconds + " s" : seconds / 60 + " min";
    }
}
