package io.factry.historian.gateway;

import io.factry.historian.proto.CreateMeasurement;
import io.factry.historian.proto.CreateMeasurementsRequest;
import io.factry.historian.proto.Measurement;
import io.factry.historian.proto.MeasurementRequest;
import io.factry.historian.proto.Measurements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ConcurrentHashMap;

public class MeasurementCache {
    private static final Logger logger = LoggerFactory.getLogger(MeasurementCache.class);

    private final ConcurrentHashMap<String, String> tagPathToUUID = new ConcurrentHashMap<>();

    public void refresh(FactryGrpcClient grpcClient) {
        try {
            MeasurementRequest request = MeasurementRequest.newBuilder().build();
            Measurements response = grpcClient.getMeasurements(request);

            tagPathToUUID.clear();
            for (Measurement m : response.getMeasurementsList()) {
                tagPathToUUID.put(m.getName(), m.getUuid());
            }
            logger.info("Measurement cache refreshed, {} measurements loaded", tagPathToUUID.size());
        } catch (Exception e) {
            logger.error("Failed to refresh measurement cache", e);
        }
    }

    public String getOrCreateUUID(String tagPath, FactryGrpcClient grpcClient, Object value) {
        String uuid = tagPathToUUID.get(tagPath);
        if (uuid != null) {
            return uuid;
        }

        String dataType = toFactryDataType(value);
        logger.info("Measurement not found in cache for '{}', creating via autoOnboard with dataType={}", tagPath, dataType);
        try {
            CreateMeasurement.Builder builder = CreateMeasurement.newBuilder()
                    .setName(tagPath)
                    .setAutoOnboard(true);
            if (dataType != null) {
                builder.setDataType(dataType);
            }
            CreateMeasurement createMeasurement = builder.build();

            CreateMeasurementsRequest request = CreateMeasurementsRequest.newBuilder()
                    .addMeasurements(createMeasurement)
                    .build();

            grpcClient.createMeasurements(request);
            refresh(grpcClient);

            uuid = tagPathToUUID.get(tagPath);
            if (uuid != null) {
                return uuid;
            }

            logger.warn("Measurement UUID still not found after create+refresh for '{}'", tagPath);
            return "";
        } catch (Exception e) {
            logger.error("Failed to create measurement for tag path: " + tagPath, e);
            return "";
        }
    }

    public int size() {
        return tagPathToUUID.size();
    }

    private static String toFactryDataType(Object value) {
        if (value instanceof Boolean) {
            return "boolean";
        } else if (value instanceof Number) {
            return "number";
        } else if (value instanceof String) {
            return "string";
        }
        return null;
    }
}
