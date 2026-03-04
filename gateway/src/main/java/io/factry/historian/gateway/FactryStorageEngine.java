package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.storage.AbstractStorageEngine;
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
    private final FactryHistorianSettings settings;
    private final FactryGrpcClient grpcClient;
    private final MeasurementCache measurementCache;

    public FactryStorageEngine(
        GatewayContext context,
        String historianName,
        FactryHistorianSettings settings,
        FactryGrpcClient grpcClient,
        MeasurementCache measurementCache
    ) {
        super(context, historianName, LoggerEx.newBuilder().build(FactryStorageEngine.class));
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
                String tagPath = point.source().toString();
                String measurementUUID = measurementCache.getOrCreateUUID(tagPath, grpcClient);

                Point.Builder pb = Point.newBuilder()
                        .setMeasurementUUID(measurementUUID)
                        .setTimestamp(Timestamps.fromMillis(point.timestamp().toEpochMilli()))
                        .setStatus(qualityToStatus(point.quality().getCode()));

                Object value = point.value();
                if (value instanceof Boolean) {
                    pb.setValue(Value.newBuilder().setBoolValue((Boolean) value).build());
                } else if (value instanceof Number) {
                    pb.setValue(Value.newBuilder().setNumberValue(((Number) value).doubleValue()).build());
                } else if (value != null) {
                    pb.setValue(Value.newBuilder().setStringValue(value.toString()).build());
                }

                pointsBuilder.addPoints(pb.build());
            }

            grpcClient.createPoints(pointsBuilder.build());

            if (settings.isDebugLogging()) {
                logger.debug("gRPC createPoints succeeded for " + points.size() + " points");
            }
            return StorageResult.success(points);

        } catch (Exception e) {
            logger.error("Error storing atomic points via gRPC", e);
            return StorageResult.failure(points);
        }
    }

    @Override
    protected StorageResult<SourceChangePoint> applySourceChanges(List<SourceChangePoint> changes) {
        logger.debug("applySourceChanges called with " + changes.size() + " changes");
        return StorageResult.success(changes);
    }

    @Override
    protected boolean isEngineUnavailable() {
        return grpcClient.isShutdown();
    }

    public void shutdown() {
        logger.info("Shutting down Factry Storage Engine");
    }

    private static String qualityToStatus(int qualityCode) {
        if (qualityCode >= 192) {
            return "good";
        } else if (qualityCode >= 64) {
            return "uncertain";
        } else {
            return "bad";
        }
    }
}
