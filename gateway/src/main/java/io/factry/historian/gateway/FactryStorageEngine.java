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

import java.util.ArrayList;
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
        logger.info("doStoreAtomic called with " + points.size() + " points");

        try {
            Points.Builder pointsBuilder = Points.newBuilder();
            List<AtomicPoint<?>> skipped = new ArrayList<>();
            int built = 0;

            for (AtomicPoint<?> point : points) {
                String tagPath = point.source().toString();
                Object value = point.value();
                Class<?> valueClass = point.valueClass().orElse(null);

                logger.info("Processing point: tagPath=" + tagPath
                        + ", value=" + value
                        + ", valueClass=" + (valueClass != null ? valueClass.getName() : "null")
                        + ", quality=" + point.quality().getCode()
                        + ", timestamp=" + point.timestamp());

                String measurementUUID = measurementCache.getOrCreateUUID(tagPath, grpcClient, valueClass);

                if (measurementUUID == null || measurementUUID.isEmpty()) {
                    logger.warn("Skipping point for '" + tagPath + "': no measurement UUID resolved");
                    skipped.add(point);
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
                } else if (value != null) {
                    pb.setValue(Value.newBuilder().setStringValue(value.toString()).build());
                } else {
                    logger.warn("Point for '" + tagPath + "' has null value, skipping");
                    skipped.add(point);
                    continue;
                }

                pointsBuilder.addPoints(pb.build());
                built++;
            }

            if (built > 0) {
                logger.info("Sending " + built + " points via gRPC createPoints");
                grpcClient.createPoints(pointsBuilder.build());
                logger.info("gRPC createPoints succeeded for " + built + " points");
            } else {
                logger.warn("No valid points to send (all " + points.size() + " were skipped)");
            }

            if (!skipped.isEmpty()) {
                logger.warn("Returning failure for " + skipped.size() + " skipped points");
                return StorageResult.failure(skipped);
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
        boolean unavailable = grpcClient.isShutdown();
        if (unavailable) {
            logger.warn("gRPC client is shutdown — engine unavailable");
        }
        return unavailable;
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
