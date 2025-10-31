package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.storage.AbstractStorageEngine;
import com.inductiveautomation.historian.common.model.data.AtomicPoint;
import com.inductiveautomation.historian.common.model.data.SourceChangePoint;
import com.inductiveautomation.historian.common.model.data.StorageResult;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Storage engine for writing historical data to the Factry Historian system.
 *
 * This implementation sends data to the proxy REST API at /collector endpoint.
 */
public class FactryStorageEngine extends AbstractStorageEngine {
    private final FactryHistorianSettings settings;
    private final FactryHttpClient httpClient;

    public FactryStorageEngine(
        GatewayContext context,
        String historianName,
        FactryHistorianSettings settings
    ) {
        super(context, historianName, LoggerEx.newBuilder().build(FactryStorageEngine.class));
        this.settings = settings;
        this.httpClient = new FactryHttpClient(settings);
        logger.info("Factry Storage Engine initialized with proxy URL: " + settings.getUrl());
    }

    @Override
    protected StorageResult<AtomicPoint<?>> doStoreAtomic(List<AtomicPoint<?>> points) {
        if (settings.isDebugLogging()) {
            logger.debug("doStoreAtomic called with " + points.size() + " points");
        }

        try {
            // TODO: Implement actual HTTP POST to proxy /collector endpoint
            // For now, just log the points

            logger.info("Would store " + points.size() + " atomic points to " + settings.getUrl() + "/collector");

            if (settings.isDebugLogging()) {
                logger.debug("Received " + points.size() + " atomic points for storage");
                // Log details of first point as example
                if (!points.isEmpty()) {
                    AtomicPoint<?> first = points.get(0);
                    logger.debug("Example point - source: " + first.source() +
                               ", timestamp: " + first.timestamp() +
                               ", value: " + first.value() +
                               ", quality: " + first.quality());
                }
            }

            // Return success result
            return StorageResult.success(points);

        } catch (Exception e) {
            logger.error("Error storing atomic points", e);
            return StorageResult.failure(points);
        }
    }

    @Override
    protected StorageResult<SourceChangePoint> applySourceChanges(List<SourceChangePoint> changes) {
        logger.debug("applySourceChanges called with " + changes.size() + " changes");

        try {
            // TODO: Implement source change application
            logger.info("Would apply " + changes.size() + " source changes");
            return StorageResult.success(changes);

        } catch (Exception e) {
            logger.error("Error applying source changes", e);
            return StorageResult.failure(changes);
        }
    }

    @Override
    protected boolean isEngineUnavailable() {
        // TODO: Check if proxy is reachable
        // For now, assume always available
        return false;
    }
}
