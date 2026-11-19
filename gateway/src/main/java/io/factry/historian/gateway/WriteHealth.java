package io.factry.historian.gateway;

import com.inductiveautomation.ignition.common.i18n.LocalizedString;
import com.inductiveautomation.ignition.gateway.model.ProfileStatus;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;

/**
 * What the profile status says, from the connection check and from Factry's answers to writes.
 * A failed check says why: unreachable, a refused token or a deleted collector.
 * The connection check (GetCollector) passes while Factry refuses every CreatePoints, for
 * example INTERNAL while its InfluxDB is down; store and forward keeps that data and retries it,
 * but the profile would show Running. So writes that are refused, and only refused, for
 * {@link #REFUSING_AFTER_MS} turn the status Errored, until Factry accepts one again.
 */
final class WriteHealth {
    private static final Logger logger = LoggerFactory.getLogger(WriteHealth.class);

    /** One connection check interval: a single refused flush is not a reason to alarm anyone. */
    static final long REFUSING_AFTER_MS = 30_000;

    private final LongSupplier clock;

    /** Factry's answer to the last connection check; null until the first one. */
    private Status check;
    /** When Factry refused the first write after the last accepted one; null while it accepts. */
    private Long refusedSince;
    private Status lastRefusal;
    /** Whether the switch to refusing was logged. */
    private boolean refusingLogged;

    WriteHealth() {
        this(System::currentTimeMillis);
    }

    WriteHealth(LongSupplier clock) {
        this.clock = clock;
    }

    /**
     * Record the connection check. Logs one WARN when the reason it fails changes, rather than
     * a line per check, and one INFO when it passes again.
     */
    synchronized void connectionChecked(Status status) {
        Status previous = check;
        check = status;
        if (!status.isOk() && (previous == null || previous.isOk()
                || checkFailure(previous) != checkFailure(status) || previous.getCode() != status.getCode())) {
            logger.warn(checkFailure(status).details(status));
        } else if (status.isOk() && previous != null && !previous.isOk()) {
            logger.info("Factry Historian answers the connection check again");
        }
        logIfRefusing();
    }

    /** Factry answered OK to a CreatePoints. */
    synchronized void writeAccepted() {
        if (refusingLogged) {
            logger.info("Factry accepts writes again");
        }
        refusedSince = null;
        lastRefusal = null;
        refusingLogged = false;
    }

    /** Factry answered a CreatePoints with an error. Not for UNAVAILABLE or DEADLINE_EXCEEDED. */
    synchronized void writeRefused(Status status) {
        if (refusedSince == null) {
            refusedSince = clock.getAsLong();
        }
        lastRefusal = status;
        logIfRefusing();
    }

    /** Forget the writes, for new settings: they are judged afresh. */
    synchronized void clearWrites() {
        refusedSince = null;
        lastRefusal = null;
        refusingLogged = false;
    }

    synchronized ProfileStatus status() {
        if (check == null) {
            return ProfileStatus.UNKNOWN;
        }
        if (!check.isOk()) {
            CheckFailure failure = checkFailure(check);
            return new ProfileStatus(ProfileStatus.State.Errored, LocalizedString.createRaw(failure.message),
                    failure.details(check));
        }
        if (refusing()) {
            // The gateway's historian page shows an Errored profile as "Error" with the details as
            // its tooltip, so the details carry the whole story.
            return new ProfileStatus(ProfileStatus.State.Errored,
                    LocalizedString.createRaw("Factry refuses writes"), refusingDetails());
        }
        return ProfileStatus.RUNNING;
    }

    /** The collector health for Factry: Unstable while the status says Factry refuses writes. */
    synchronized String health() {
        return refusing() ? "Unstable" : "Collecting";
    }

    private boolean refusing() {
        return refusedSince != null && clock.getAsLong() - refusedSince >= REFUSING_AFTER_MS;
    }

    private String refusingDetails() {
        return "Factry has refused every write for " + duration(clock.getAsLong() - refusedSince)
                + " (last answer: " + describe(lastRefusal) + "). Store and forward keeps the data and"
                + " retries it.";
    }

    private void logIfRefusing() {
        if (!refusingLogged && refusing()) {
            refusingLogged = true;
            logger.warn(refusingDetails() + " The profile shows Errored until Factry accepts a write.");
        }
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
