package io.factry.historian.gateway;

import io.factry.historian.proto.CreateMeasurement;
import io.factry.historian.proto.CreateMeasurementsRequest;
import io.factry.historian.proto.Measurement;
import io.factry.historian.proto.MeasurementRequest;
import io.factry.historian.proto.Measurements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MeasurementCache {
    private static final Logger logger = LoggerFactory.getLogger(MeasurementCache.class);

    private final ConcurrentHashMap<String, String> tagPathToUUID = new ConcurrentHashMap<>();

    public void refresh(FactryGrpcClient grpcClient) {
        try {
            MeasurementRequest request = MeasurementRequest.newBuilder().build();
            Measurements response = grpcClient.getMeasurements(request);

            // Build new map first, then merge — never clear the existing cache
            // to avoid race conditions with concurrent store calls
            Map<String, String> fresh = new HashMap<>();
            for (Measurement m : response.getMeasurementsList()) {
                fresh.put(m.getName(), m.getUuid());
                logger.debug("Measurement from Factry: name='{}', uuid='{}', status='{}', datatype='{}'",
                        m.getName(), m.getUuid(), m.getStatus(), m.getDatatype());
            }
            tagPathToUUID.putAll(fresh);
            logger.info("Measurement cache refreshed, {} measurements from Factry, {} total in cache",
                    fresh.size(), tagPathToUUID.size());
        } catch (Exception e) {
            logger.error("Failed to refresh measurement cache", e);
        }
    }

    public String getOrCreateUUID(String tagPath, FactryGrpcClient grpcClient, Class<?> valueClass) {
        String uuid = tagPathToUUID.get(tagPath);
        if (uuid != null) {
            return uuid;
        }

        String dataType = toFactryDataType(valueClass);
        logger.info("Measurement not found in cache for '{}', creating via autoOnboard with dataType={}, valueClass={}",
                tagPath, dataType, valueClass != null ? valueClass.getName() : "null");

        if (dataType == null) {
            logger.warn("Cannot create measurement for '{}': data type unknown (valueClass=null). "
                    + "Skipping until a point with a known type arrives.", tagPath);
            return "";
        }

        try {
            CreateMeasurement createMeasurement = CreateMeasurement.newBuilder()
                    .setName(tagPath)
                    .setAutoOnboard(true)
                    .setDataType(dataType)
                    .build();

            CreateMeasurementsRequest request = CreateMeasurementsRequest.newBuilder()
                    .addMeasurements(createMeasurement)
                    .build();

            logger.info("Calling createMeasurements for '{}'", tagPath);
            grpcClient.createMeasurements(request);
            logger.info("createMeasurements succeeded for '{}'", tagPath);

            // Refresh and check — single attempt, no sleep
            refresh(grpcClient);
            uuid = tagPathToUUID.get(tagPath);
            if (uuid != null) {
                logger.info("Measurement UUID resolved for '{}': {}", tagPath, uuid);
                return uuid;
            }

            logger.warn("Measurement UUID not found after create+refresh for '{}'. "
                    + "Cache keys: {}", tagPath, tagPathToUUID.keySet());
            return "";
        } catch (Exception e) {
            logger.error("Failed to create measurement for tag path: " + tagPath, e);
            return "";
        }
    }

    public int size() {
        return tagPathToUUID.size();
    }

    private static String toFactryDataType(Class<?> valueClass) {
        if (valueClass == null) {
            return null;
        }
        if (Boolean.class.isAssignableFrom(valueClass)) {
            return "boolean";
        } else if (Number.class.isAssignableFrom(valueClass)) {
            return "number";
        } else if (String.class.isAssignableFrom(valueClass)) {
            return "string";
        }
        return null;
    }
}
