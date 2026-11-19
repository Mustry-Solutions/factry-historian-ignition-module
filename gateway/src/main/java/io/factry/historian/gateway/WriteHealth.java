package io.factry.historian.gateway;

import com.inductiveautomation.ignition.common.i18n.LocalizedString;
import com.inductiveautomation.ignition.gateway.model.ProfileStatus;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.LongSupplier;

/**
 * What the profile status says, from the connection check and from Factry's answers to writes.
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

    /** Null until the first connection check. */
    private Boolean connected;
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

    synchronized void connectionChecked(boolean ok) {
        connected = ok;
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
        if (connected == null) {
            return ProfileStatus.UNKNOWN;
        }
        if (!connected) {
            return ProfileStatus.ERRORED;
        }
        if (refusing()) {
            // The gateway's historian page shows an Errored profile as "Error" with the details as
            // its tooltip, so the details carry the whole story.
            return new ProfileStatus(ProfileStatus.State.Errored,
                    LocalizedString.createRaw("Factry refuses writes"), refusingDetails());
        }
        return ProfileStatus.RUNNING;
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
