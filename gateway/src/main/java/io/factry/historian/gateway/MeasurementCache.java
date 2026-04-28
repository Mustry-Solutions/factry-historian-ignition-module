package io.factry.historian.gateway;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.factry.historian.proto.Asset;
import io.factry.historian.proto.Assets;
import io.factry.historian.proto.CreateMeasurement;
import io.factry.historian.proto.CreateMeasurementsRequest;
import io.factry.historian.proto.GetAssetsRequest;
import io.factry.historian.proto.Measurement;
import io.factry.historian.proto.MeasurementRequest;
import io.factry.historian.proto.Measurements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MeasurementCache {
    private static final Logger logger = LoggerFactory.getLogger(MeasurementCache.class);

    private final ConcurrentHashMap<String, String> tagPathToUUID = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Measurement> uuidToMeasurement = new ConcurrentHashMap<>();
    private final Set<String> pendingCreations = ConcurrentHashMap.newKeySet();

    private final ConcurrentHashMap<String, String> assetNameToUUID = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Asset> uuidToAsset = new ConcurrentHashMap<>();

    /** Metadata properties cached from doStoreMetadata, applied when creating measurements. */
    private final ConcurrentHashMap<String, Map<String, String>> pendingMetadata = new ConcurrentHashMap<>();

    public void refresh(FactryGrpcClient grpcClient) {
        try {
            MeasurementRequest request = MeasurementRequest.newBuilder().build();
            Measurements response = grpcClient.getMeasurements(request);

            Map<String, String> freshPaths = new HashMap<>();
            Map<String, Measurement> freshMeasurements = new HashMap<>();
            int total = 0;
            for (Measurement m : response.getMeasurementsList()) {
                total++;
                if ("active".equalsIgnoreCase(m.getStatus())) {
                    freshPaths.put(m.getName(), m.getUuid());
                    freshMeasurements.put(m.getUuid(), m);
                } else {
                    logger.debug("Skipping measurement '{}' with status '{}'", m.getName(), m.getStatus());
                }
            }
            // Replace maps entirely so deleted measurements don't linger
            tagPathToUUID.clear();
            tagPathToUUID.putAll(freshPaths);
            uuidToMeasurement.clear();
            uuidToMeasurement.putAll(freshMeasurements);
            logger.debug("Measurement cache refreshed, {} active of {} total from Factry, {} in cache",
                    freshPaths.size(), total, tagPathToUUID.size());

            // Fetch assets
            try {
                Assets assetsResponse = grpcClient.getAssets();
                Map<String, String> freshAssetNames = new HashMap<>();
                Map<String, Asset> freshAssets = new HashMap<>();
                for (Asset a : assetsResponse.getAssetsList()) {
                    freshAssetNames.put(a.getName(), a.getUuid());
                    freshAssets.put(a.getUuid(), a);
                }
                assetNameToUUID.clear();
                assetNameToUUID.putAll(freshAssetNames);
                uuidToAsset.clear();
                uuidToAsset.putAll(freshAssets);
                logger.debug("Asset cache refreshed, {} active", freshAssetNames.size());
            } catch (Exception ae) {
                logger.error("Failed to refresh asset cache", ae);
            }

        } catch (Exception e) {
            logger.error("Failed to refresh measurement cache", e);
        }
    }

    public String getOrCreateUUID(String tagPath, FactryGrpcClient grpcClient, Object value) {
        // Fast path: already cached
        String uuid = tagPathToUUID.get(tagPath);
        if (uuid != null) {
            logger.debug("Cache hit for '{}': uuid={}", tagPath, uuid);
            return uuid;
        }
        logger.debug("Cache miss for '{}', cache size={}, keys={}", tagPath, tagPathToUUID.size(), tagPathToUUID.keySet());

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

            logger.debug("Creating measurement for '{}' with dataType={}", tagPath, dataType);

            CreateMeasurement.Builder builder = CreateMeasurement.newBuilder()
                    .setName(tagPath)
                    .setAutoOnboard(true)
                    .setDataType(dataType);

            Map<String, String> metadata = pendingMetadata.remove(tagPath);
            if (metadata != null && !metadata.isEmpty()) {
                String description = metadata.remove("description");
                if (description != null) {
                    builder.setDescription(description);
                }
                if (!metadata.isEmpty()) {
                    Struct.Builder attrs = Struct.newBuilder();
                    for (Map.Entry<String, String> entry : metadata.entrySet()) {
                        attrs.putFields(entry.getKey(),
                                Value.newBuilder().setStringValue(entry.getValue()).build());
                    }
                    builder.setAttributes(attrs);
                }
                logger.debug("Applied cached metadata to new measurement '{}'", tagPath);
            }

            CreateMeasurement createMeasurement = builder.build();

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
                    logger.debug("Measurement UUID resolved for '{}' on attempt {}: {}", tagPath, attempt, uuid);
                    return uuid;
                }
                logger.debug("Measurement '{}' not visible yet, attempt {}/5", tagPath, attempt);
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

    /**
     * Remove a measurement from the cache by UUID so the next
     * {@link #getOrCreateUUID} call will re-create it in Factry.
     */
    public void evictByUUID(String uuid) {
        uuidToMeasurement.remove(uuid);
        tagPathToUUID.entrySet().removeIf(e -> e.getValue().equals(uuid));
        logger.info("Evicted measurement UUID '{}' from cache", uuid);
    }

    public int size() {
        return tagPathToUUID.size();
    }

    public String getUUID(String tagPath) {
        return tagPathToUUID.get(tagPath);
    }

    public Measurement getMeasurementByUUID(String uuid) {
        return uuidToMeasurement.get(uuid);
    }

    public Measurement getMeasurementByName(String name) {
        String uuid = tagPathToUUID.get(name);
        return uuid != null ? uuidToMeasurement.get(uuid) : null;
    }

    public Collection<Measurement> getAllMeasurements() {
        return uuidToMeasurement.values();
    }

    // --- Asset accessors ---

    public String getAssetUUID(String name) {
        return assetNameToUUID.get(name);
    }

    public Asset getAssetByName(String name) {
        String uuid = assetNameToUUID.get(name);
        return uuid != null ? uuidToAsset.get(uuid) : null;
    }

    public Collection<Asset> getAllAssets() {
        return uuidToAsset.values();
    }

    /**
     * Cache metadata properties for a tag path. These will be applied as initial
     * values when the measurement is created in Factry via {@link #getOrCreateUUID}.
     */
    public void storeMetadata(String tagPath, Map<String, String> properties) {
        if (properties == null || properties.isEmpty()) {
            return;
        }
        pendingMetadata.merge(tagPath, new HashMap<>(properties), (existing, incoming) -> {
            existing.putAll(incoming);
            return existing;
        });
        logger.debug("Cached metadata for '{}': {}", tagPath, properties);
    }

    static String toFactryDataType(Object value) {
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
