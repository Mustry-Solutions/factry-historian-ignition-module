package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.storage.AbstractStorageEngine;
import com.inductiveautomation.historian.gateway.api.paths.QualifiedPathAdapter;
import com.inductiveautomation.historian.gateway.api.storage.strategy.ImmediateStorageStrategy;
import com.inductiveautomation.historian.common.model.data.AtomicPoint;
import com.inductiveautomation.historian.common.model.data.SourceChangePoint;
import com.inductiveautomation.historian.common.model.data.StorageResult;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import com.google.protobuf.util.Timestamps;
import com.google.protobuf.Value;
import io.factry.historian.proto.Point;
import io.factry.historian.proto.Points;

import java.util.List;

public class FactryStorageEngine extends AbstractStorageEngine {
    private volatile FactryHistorianSettings settings;
    private final FactryGrpcClient grpcClient;
    private final MeasurementCache measurementCache;

    public FactryStorageEngine(
        GatewayContext context,
        String historianName,
        FactryHistorianSettings settings,
        FactryGrpcClient grpcClient,
        MeasurementCache measurementCache
    ) {
        super(context, historianName, LoggerEx.newBuilder().build(FactryStorageEngine.class),
                QualifiedPathAdapter.DEFAULT, ImmediateStorageStrategy.create(historianName));
        this.settings = settings;
        this.grpcClient = grpcClient;
        this.measurementCache = measurementCache;
        logger.info("Factry Storage Engine initialized with gRPC target: " +
                settings.getGrpcHost() + ":" + settings.getGrpcPort());
    }

    @Override
    protected StorageResult<AtomicPoint<?>> doStoreAtomic(List<AtomicPoint<?>> points) {
        if (settings.isDebugLogging()) {
            logger.debug("doStoreAtomic called with " + points.size() + " points");
        }

        try {
            Points.Builder pointsBuilder = Points.newBuilder();

            for (AtomicPoint<?> point : points) {
                String tagPath = TagPathUtil.qualifiedPathToStoredPath(point.source().toString());
                Object value = point.value();

                logger.info("Point: tagPath=" + tagPath
                        + ", value=" + value
                        + ", valueType=" + (value != null ? value.getClass().getName() : "null"));

                String measurementUUID = measurementCache.getOrCreateUUID(tagPath, grpcClient, value);
                if (measurementUUID == null || measurementUUID.isEmpty()) {
                    logger.info("Skipping point for '" + tagPath + "': no measurement UUID");
                    continue;
                }

                if (value == null) {
                    logger.info("Skipping point for '" + tagPath + "': null value");
                    continue;
                }

                Point.Builder pb = Point.newBuilder()
                        .setMeasurementUUID(measurementUUID)
                        .setTimestamp(Timestamps.fromMillis(point.timestamp().toEpochMilli()))
                        .setStatus(qualityToStatus(point.quality().getCode()));

                if (value instanceof Boolean) {
                    pb.setValue(Value.newBuilder().setBoolValue((Boolean) value).build());
                } else if (value instanceof Number) {
                    pb.setValue(Value.newBuilder().setNumberValue(((Number) value).doubleValue()).build());
                } else {
                    pb.setValue(Value.newBuilder().setStringValue(value.toString()).build());
                }

                pointsBuilder.addPoints(pb.build());
            }

            int pointCount = pointsBuilder.getPointsCount();
            if (pointCount == 0) {
                logger.info("No points to send (all skipped)");
                return StorageResult.success(points);
            }

            logger.info("Sending " + pointCount + " points via createPoints");
            grpcClient.createPoints(pointsBuilder.build());
            logger.info("createPoints succeeded for " + pointCount + " points");

            if (settings.isDebugLogging()) {
                logger.debug("gRPC createPoints succeeded for " + points.size() + " points");
            }
            return StorageResult.success(points);

        } catch (Exception e) {
            logger.error("Error storing atomic points via gRPC", e);
            // Use exception() instead of failure() so the S&F sink bridge
            // sees the error and throws DataStorageException, triggering retry.
            return StorageResult.exception(e, points);
        }
    }

    @Override
    protected StorageResult<SourceChangePoint> applySourceChanges(List<SourceChangePoint> changes) {
        // Measurement lifecycle is managed by the Factry platform, not by this collector.
        // No retirement action needed — just acknowledge the changes.
        logger.debug("applySourceChanges called with " + changes.size() + " changes (no-op for Factry)");
        return StorageResult.success(changes);
    }

    @Override
    protected boolean isEngineUnavailable() {
        // When the gRPC connection is down, return true so S&F buffers points
        // in the pending queue without attempting the call. This prevents data loss
        // during the timeout window and avoids unnecessary quarantine.
        // The connection is marked as available again on the next successful gRPC call
        // or via the periodic connection test in FactryHistoryProvider.getStatus().
        return !grpcClient.isConnected();
    }

    void updateSettings(FactryHistorianSettings newSettings) {
        this.settings = newSettings;
    }

    public void shutdown() {
        logger.info("Shutting down Factry Storage Engine");
    }

    private static String qualityToStatus(int qualityCode) {
        if (qualityCode >= 192) {
            return "Good";
        } else if (qualityCode >= 64) {
            return "Uncertain";
        } else {
            return "Bad";
        }
    }
}
