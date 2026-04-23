package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.storage.AbstractStorageEngine;
import com.inductiveautomation.historian.gateway.api.paths.QualifiedPathAdapter;
import com.inductiveautomation.historian.gateway.api.storage.strategy.ImmediateStorageStrategy;
import com.inductiveautomation.historian.common.model.data.AtomicPoint;
import com.inductiveautomation.historian.common.model.data.MetadataPoint;
import com.inductiveautomation.historian.common.model.data.SourceChangePoint;
import com.inductiveautomation.historian.common.model.data.StorageResult;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import com.google.protobuf.util.Timestamps;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import com.google.protobuf.Value;
import io.factry.historian.proto.Point;
import io.factry.historian.proto.Points;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FactryStorageEngine extends AbstractStorageEngine {
    private volatile FactryHistorianSettings settings;
    private final FactryGrpcClient grpcClient;
    private final MeasurementCache measurementCache;
    private final HistorianMetrics metrics;

    public FactryStorageEngine(
        GatewayContext context,
        String historianName,
        FactryHistorianSettings settings,
        FactryGrpcClient grpcClient,
        MeasurementCache measurementCache,
        HistorianMetrics metrics
    ) {
        super(context, historianName, LoggerEx.newBuilder().build(FactryStorageEngine.class),
                QualifiedPathAdapter.DEFAULT, ImmediateStorageStrategy.create(historianName));
        this.settings = settings;
        this.grpcClient = grpcClient;
        this.measurementCache = measurementCache;
        this.metrics = metrics;
        logger.debug("Factry Storage Engine initialized with gRPC target: " +
                settings.getGrpcHost() + ":" + settings.getGrpcPort());
    }

    @Override
    protected StorageResult<AtomicPoint<?>> doStoreAtomic(List<AtomicPoint<?>> points) {
        if (settings.isDebugLogging()) {
            logger.debug("doStoreAtomic called with " + points.size() + " points");
        }

        long startMs = System.currentTimeMillis();
        try {
            return sendPoints(points, true);
        } catch (StatusRuntimeException e) {
            metrics.recordStoreError();
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                logger.warn("Connection error storing points, S&F will retry: " + e.getMessage());
                return StorageResult.exception(e, points);
            }
            logger.error("Server rejected points (" + code + "): " + e.getMessage());
            return StorageResult.failure(points);
        } catch (Exception e) {
            metrics.recordStoreError();
            logger.error("Unexpected error storing points via gRPC", e);
            return StorageResult.exception(e, points);
        }
    }

    /**
     * Build and send points to Factry. If the call fails with a server rejection
     * (e.g. deleted measurement) and {@code retryOnRejection} is true, evict the
     * affected measurement UUIDs from the cache and retry once — the retry will
     * trigger {@link MeasurementCache#getOrCreateUUID} which re-creates the
     * measurement in Factry.
     */
    private StorageResult<AtomicPoint<?>> sendPoints(List<AtomicPoint<?>> points, boolean retryOnRejection) {
        BuildResult built = buildPoints(points);

        if (built.pointsBuilder.getPointsCount() == 0) {
            logger.debug("No points to send (all skipped)");
            return StorageResult.success(points);
        }

        try {
            logger.debug("Sending " + built.pointsBuilder.getPointsCount() + " points via createPoints");
            grpcClient.createPoints(built.pointsBuilder.build());
            long elapsedMs = System.currentTimeMillis();
            metrics.recordStore(built.pointsBuilder.getPointsCount(), elapsedMs);
            logger.debug("createPoints succeeded for " + built.pointsBuilder.getPointsCount() + " points");

            if (settings.isDebugLogging()) {
                logger.debug("gRPC createPoints succeeded for " + points.size() + " points");
            }
            return StorageResult.success(points);

        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();

            // Connection errors — let caller handle (S&F retry)
            if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                throw e;
            }

            // Server rejection — likely deleted measurements. Evict and retry once.
            if (retryOnRejection && !built.usedUUIDs.isEmpty()) {
                logger.warn("Server rejected points (" + code + "), evicting "
                        + built.usedUUIDs.size() + " measurement UUIDs and retrying: " + e.getMessage());
                for (String uuid : built.usedUUIDs) {
                    measurementCache.evictByUUID(uuid);
                }
                return sendPoints(points, false);
            }

            throw e;
        }
    }

    private static class BuildResult {
        final Points.Builder pointsBuilder;
        final Set<String> usedUUIDs;

        BuildResult(Points.Builder pointsBuilder, Set<String> usedUUIDs) {
            this.pointsBuilder = pointsBuilder;
            this.usedUUIDs = usedUUIDs;
        }
    }

    private BuildResult buildPoints(List<AtomicPoint<?>> points) {
        Points.Builder pointsBuilder = Points.newBuilder();
        Set<String> usedUUIDs = new HashSet<>();

        for (AtomicPoint<?> point : points) {
            String tagPath = TagPathUtil.qualifiedPathToStoredPath(point.source().toString());
            Object value = point.value();

            logger.debug("Point: tagPath=" + tagPath
                    + ", value=" + value
                    + ", valueType=" + (value != null ? value.getClass().getName() : "null"));

            String measurementUUID = measurementCache.getOrCreateUUID(tagPath, grpcClient, value);
            if (measurementUUID == null || measurementUUID.isEmpty()) {
                logger.debug("Skipping point for '" + tagPath + "': no measurement UUID");
                continue;
            }

            if (value == null) {
                logger.debug("Skipping point for '" + tagPath + "': null value");
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
            usedUUIDs.add(measurementUUID);
        }

        return new BuildResult(pointsBuilder, usedUUIDs);
    }

    @Override
    protected StorageResult<MetadataPoint> doStoreMetadata(List<MetadataPoint> metadataPoints) {
        // Metadata is managed by the Factry platform, not by this collector.
        // Acknowledge the points without storing — metadata lives in Factry's database.
        logger.debug("doStoreMetadata called with " + metadataPoints.size() + " points (no-op for Factry)");
        return StorageResult.success(metadataPoints);
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

    static String qualityToStatus(int qualityCode) {
        if (qualityCode >= 192) {
            return "Good";
        } else if (qualityCode >= 64) {
            return "Uncertain";
        } else {
            return "Bad";
        }
    }
}
