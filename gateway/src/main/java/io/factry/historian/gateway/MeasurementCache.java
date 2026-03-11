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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MeasurementCache {
    private static final Logger logger = LoggerFactory.getLogger(MeasurementCache.class);

    private final ConcurrentHashMap<String, String> tagPathToUUID = new ConcurrentHashMap<>();
    private final Set<String> pendingCreations = ConcurrentHashMap.newKeySet();

    public void refresh(FactryGrpcClient grpcClient) {
        try {
            MeasurementRequest request = MeasurementRequest.newBuilder().build();
            Measurements response = grpcClient.getMeasurements(request);

            Map<String, String> fresh = new HashMap<>();
            int total = 0;
            for (Measurement m : response.getMeasurementsList()) {
                total++;
                if ("Active".equals(m.getStatus())) {
                    fresh.put(m.getName(), m.getUuid());
                } else {
                    logger.info("Skipping measurement '{}' with status '{}'", m.getName(), m.getStatus());
                }
            }
            tagPathToUUID.putAll(fresh);
            logger.info("Measurement cache refreshed, {} active of {} total from Factry, {} in cache",
                    fresh.size(), total, tagPathToUUID.size());
        } catch (Exception e) {
            logger.error("Failed to refresh measurement cache", e);
        }
    }

    public String getOrCreateUUID(String tagPath, FactryGrpcClient grpcClient, Object value) {
        // Fast path: already cached
        String uuid = tagPathToUUID.get(tagPath);
        if (uuid != null) {
            logger.info("Cache hit for '{}': uuid={}", tagPath, uuid);
            return uuid;
        }
        logger.info("Cache miss for '{}', cache size={}, keys={}", tagPath, tagPathToUUID.size(), tagPathToUUID.keySet());

        // Determine data type from value — refuse to create without it
        String dataType = toFactryDataType(value);
        if (dataType == null) {
            logger.debug("Skipping measurement creation for '{}': value is null or unknown type", tagPath);
            return "";
        }

        // Prevent concurrent creation for the same tag path
        if (!pendingCreations.add(tagPath)) {
            logger.debug("Measurement creation already in progress for '{}'", tagPath);
            return "";
        }

        try {
            // Double-check cache after acquiring the "lock"
            uuid = tagPathToUUID.get(tagPath);
            if (uuid != null) {
                return uuid;
            }

            logger.info("Creating measurement for '{}' with dataType={}", tagPath, dataType);

            CreateMeasurement createMeasurement = CreateMeasurement.newBuilder()
                    .setName(tagPath)
                    .setAutoOnboard(true)
                    .setDataType(dataType)
                    .build();

            CreateMeasurementsRequest request = CreateMeasurementsRequest.newBuilder()
                    .addMeasurements(createMeasurement)
                    .build();

            grpcClient.createMeasurements(request);

            // Poll until the measurement becomes visible in Factry
            for (int attempt = 1; attempt <= 5; attempt++) {
                try {
                    Thread.sleep(attempt * 500L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }

                refresh(grpcClient);
                uuid = tagPathToUUID.get(tagPath);
                if (uuid != null) {
                    logger.info("Measurement UUID resolved for '{}' on attempt {}: {}", tagPath, attempt, uuid);
                    return uuid;
                }
                logger.info("Measurement '{}' not visible yet, attempt {}/5", tagPath, attempt);
            }

            logger.warn("Measurement UUID not found after create + retries for '{}'", tagPath);
            return "";
        } catch (Exception e) {
            logger.error("Failed to create measurement for tag path: " + tagPath, e);
            return "";
        } finally {
            pendingCreations.remove(tagPath);
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
