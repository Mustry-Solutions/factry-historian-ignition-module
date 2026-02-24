package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.storage.AbstractStorageEngine;
import com.inductiveautomation.historian.common.model.data.AtomicPoint;
import com.inductiveautomation.historian.common.model.data.SourceChangePoint;
import com.inductiveautomation.historian.common.model.data.StorageResult;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import io.factry.historian.proto.StoreRequest;
import io.factry.historian.proto.StoreResponse;
import io.factry.historian.proto.TagSample;

import java.util.List;

/**
 * Storage engine for writing historical data to the Factry Historian system via gRPC.
 */
public class FactryStorageEngine extends AbstractStorageEngine {
    private final FactryHistorianSettings settings;
    private final FactryGrpcClient grpcClient;

    public FactryStorageEngine(
        GatewayContext context,
        String historianName,
        FactryHistorianSettings settings
    ) {
        super(context, historianName, LoggerEx.newBuilder().build(FactryStorageEngine.class));
        this.settings = settings;
        this.grpcClient = new FactryGrpcClient(settings.getGrpcHost(), settings.getGrpcPort());
        logger.info("Factry Storage Engine initialized with gRPC target: " +
                settings.getGrpcHost() + ":" + settings.getGrpcPort());
    }

    @Override
    protected StorageResult<AtomicPoint<?>> doStoreAtomic(List<AtomicPoint<?>> points) {
        if (settings.isDebugLogging()) {
            logger.debug("doStoreAtomic called with " + points.size() + " points");
        }

        try {
            StoreRequest.Builder requestBuilder = StoreRequest.newBuilder();

            for (AtomicPoint<?> point : points) {
                TagSample.Builder sampleBuilder = TagSample.newBuilder()
                        .setTagPath(point.source().toString())
                        .setTimestampMs(point.timestamp().toEpochMilli())
                        .setQuality(point.quality().getCode());

                Object value = point.value();
                if (value instanceof Number) {
                    Number num = (Number) value;
                    if (value instanceof Double || value instanceof Float) {
                        sampleBuilder.setValueDouble(num.doubleValue());
                    } else {
                        sampleBuilder.setValueInt(num.intValue());
                        sampleBuilder.setValueDouble(num.doubleValue());
                    }
                } else if (value != null) {
                    // For non-numeric values, try to parse as double
                    try {
                        sampleBuilder.setValueDouble(Double.parseDouble(value.toString()));
                    } catch (NumberFormatException e) {
                        logger.warn("Cannot convert value to numeric: " + value);
                    }
                }

                requestBuilder.addSamples(sampleBuilder.build());
            }

            StoreResponse response = grpcClient.store(requestBuilder.build());

            if (response.getSuccess()) {
                if (settings.isDebugLogging()) {
                    logger.debug("gRPC store succeeded: " + response.getMessage() +
                            ", count=" + response.getCount());
                }
                return StorageResult.success(points);
            } else {
                logger.warn("gRPC store returned failure: " + response.getMessage());
                return StorageResult.failure(points);
            }

        } catch (Exception e) {
            logger.error("Error storing atomic points via gRPC", e);
            return StorageResult.failure(points);
        }
    }

    @Override
    protected StorageResult<SourceChangePoint> applySourceChanges(List<SourceChangePoint> changes) {
        logger.debug("applySourceChanges called with " + changes.size() + " changes");

        try {
            logger.info("Would apply " + changes.size() + " source changes");
            return StorageResult.success(changes);

        } catch (Exception e) {
            logger.error("Error applying source changes", e);
            return StorageResult.failure(changes);
        }
    }

    @Override
    protected boolean isEngineUnavailable() {
        return grpcClient.isShutdown();
    }

    /**
     * Shut down the gRPC client connection.
     */
    public void shutdown() {
        logger.info("Shutting down Factry Storage Engine gRPC client");
        grpcClient.shutdown();
    }
}
