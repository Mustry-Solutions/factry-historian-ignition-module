package io.factry.historian.gateway;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.factry.historian.proto.Asset;
import io.factry.historian.proto.AssetProperties;
import io.factry.historian.proto.AssetProperty;
import io.factry.historian.proto.Assets;
import io.factry.historian.proto.CreateMeasurement;
import io.factry.historian.proto.CreateMeasurementsRequest;
import io.factry.historian.proto.Collector;
import io.factry.historian.proto.GetAssetPropertiesRequest;
import io.factry.historian.proto.GetAssetsRequest;
import io.factry.historian.proto.GetMeasurementsByFilterRequest;
import io.factry.historian.proto.Measurement;
import io.factry.historian.proto.MetadataProperty;
import io.factry.historian.proto.MeasurementRequest;
import io.factry.historian.proto.Measurements;
import io.factry.historian.proto.Pagination;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MeasurementCache {
    private static final Logger logger = LoggerFactory.getLogger(MeasurementCache.class);

    // These lookup maps are rebuilt on every refresh and swapped in by reference (not cleared and
    // refilled in place), so a concurrent browse or query always sees a fully-populated map — never
    // an empty or half-filled one. Each field is volatile so the swapped-in reference is visible to
    // other threads immediately; the maps themselves stay ConcurrentHashMap so the occasional in-place
    // mutation (evictByUUID, targeted single-measurement inserts) remains thread-safe.
    private volatile ConcurrentHashMap<String, String> tagPathToUUID = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<String, Measurement> uuidToMeasurement = new ConcurrentHashMap<>();
    private final Set<String> pendingCreations = ConcurrentHashMap.newKeySet();

    private volatile ConcurrentHashMap<String, String> assetNameToUUID = new ConcurrentHashMap<>();
    private volatile ConcurrentHashMap<String, Asset> uuidToAsset = new ConcurrentHashMap<>();
    /** Asset UUID → list of properties belonging to that asset. */
    private volatile ConcurrentHashMap<String, List<AssetProperty>> assetProperties = new ConcurrentHashMap<>();

    /** Measurement UUID → collector name, for grouping in the browse tree. */
    private volatile ConcurrentHashMap<String, String> measurementToCollectorName = new ConcurrentHashMap<>();

    /** Metadata properties cached from doStoreMetadata, applied when creating measurements. */
    private final ConcurrentHashMap<String, Map<String, String>> pendingMetadata = new ConcurrentHashMap<>();

    private final int pageSize = ModuleProperties.getMeasurementCachePageSize();

    public void refresh(FactryGrpcClient grpcClient) {
        try {
            // Fetch all measurements using pagination
            Map<String, String> freshPaths = new HashMap<>();
            Map<String, Measurement> freshMeasurements = new HashMap<>();
            long offset = 0;
            long serverTotal = Long.MAX_VALUE;

            while (offset < serverTotal) {
                GetMeasurementsByFilterRequest request = GetMeasurementsByFilterRequest.newBuilder()
                        .setPagination(Pagination.newBuilder()
                                .setLimit(pageSize)
                                .setOffset(offset)
                                .build())
                        .build();
                Measurements response = grpcClient.getMeasurementsByFilter(request);

                if (response.getTotal() > 0) {
                    serverTotal = response.getTotal();
                }

                int pageCount = response.getMeasurementsCount();
                if (pageCount == 0) {
                    break;
                }

                for (Measurement m : response.getMeasurementsList()) {
                    if ("active".equalsIgnoreCase(m.getStatus())) {
                        freshPaths.put(m.getName(), m.getUuid());
                        freshMeasurements.put(m.getUuid(), m);
                    } else {
                        logger.debug("Skipping measurement '{}' with status '{}'", m.getName(), m.getStatus());
                    }
                }
                offset += pageCount;
            }

            // Swap in freshly-built maps by reference so deleted measurements don't linger and
            // concurrent readers never observe a half-filled map.
            tagPathToUUID = new ConcurrentHashMap<>(freshPaths);
            uuidToMeasurement = new ConcurrentHashMap<>(freshMeasurements);
            logger.info("Measurement cache refreshed, {} active of {} total from Factry",
                    freshPaths.size(), serverTotal == Long.MAX_VALUE ? offset : serverTotal);

            // Build measurement → collector name mapping
            try {
                var collectors = grpcClient.getCollectors();
                Map<String, String> collectorUUIDToName = new HashMap<>();
                for (Collector c : collectors.getCollectorsList()) {
                    String collectorName = !c.getName().isEmpty() ? c.getName() : c.getUuid();
                    collectorUUIDToName.put(c.getUuid(), collectorName);
                }

                Map<String, String> freshCollectorMap = new HashMap<>();
                for (Measurement m : freshMeasurements.values()) {
                    String collectorUUID = m.getCollectorUUID();
                    if (!collectorUUID.isEmpty()) {
                        String collectorName = collectorUUIDToName.getOrDefault(collectorUUID, collectorUUID);
                        freshCollectorMap.put(m.getUuid(), collectorName);
                    }
                }

                measurementToCollectorName = new ConcurrentHashMap<>(freshCollectorMap);
                logger.debug("Collector mapping refreshed: {} measurements mapped to collectors", freshCollectorMap.size());
            } catch (Exception ce) {
                logger.error("Failed to refresh collector mapping", ce);
            }

            // Fetch assets and their properties
            try {
                Assets assetsResponse = grpcClient.getAssets();
                Map<String, String> freshAssetNames = new HashMap<>();
                Map<String, Asset> freshAssets = new HashMap<>();
                List<String> assetUUIDs = new ArrayList<>();
                for (Asset a : assetsResponse.getAssetsList()) {
                    freshAssetNames.put(a.getName(), a.getUuid());
                    freshAssets.put(a.getUuid(), a);
                    assetUUIDs.add(a.getUuid());
                }
                assetNameToUUID = new ConcurrentHashMap<>(freshAssetNames);
                uuidToAsset = new ConcurrentHashMap<>(freshAssets);

                // Fetch properties for all assets
                Map<String, List<AssetProperty>> freshProps = new HashMap<>();
                if (!assetUUIDs.isEmpty()) {
                    try {
                        GetAssetPropertiesRequest propReq = GetAssetPropertiesRequest.newBuilder()
                                .addAllAssetUUIDs(assetUUIDs)
                                .setRecursive(true)
                                .build();
                        AssetProperties propsResponse = grpcClient.getAssetProperties(propReq);
                        for (AssetProperty prop : propsResponse.getAssetPropertiesList()) {
                            freshProps.computeIfAbsent(prop.getAssetUUID(), k -> new ArrayList<>()).add(prop);
                        }
                    } catch (Exception pe) {
                        logger.error("Failed to refresh asset properties cache", pe);
                    }
                }
                assetProperties = new ConcurrentHashMap<>(freshProps);

                logger.debug("Asset cache refreshed, {} assets, {} properties",
                        freshAssetNames.size(), freshProps.values().stream().mapToInt(List::size).sum());
            } catch (Exception ae) {
                logger.error("Failed to refresh asset cache", ae);
            }

        } catch (Exception e) {
            throw new RuntimeException("Failed to refresh measurement cache", e);
        }
    }

    /**
     * Get or create a measurement UUID, with an explicit Factry data type (e.g. "string" for arrays).
     */
    public String getOrCreateUUID(String tagPath, FactryGrpcClient grpcClient, String explicitDataType) {
        return getOrCreateUUID(tagPath, grpcClient, (Object) null, explicitDataType);
    }

    public String getOrCreateUUID(String tagPath, FactryGrpcClient grpcClient, Object value) {
        return getOrCreateUUID(tagPath, grpcClient, value, null);
    }

    private String getOrCreateUUID(String tagPath, FactryGrpcClient grpcClient, Object value, String explicitDataType) {
        // Fast path: already cached
        String uuid = tagPathToUUID.get(tagPath);
        if (uuid != null) {
            logger.debug("Cache hit for '{}': uuid={}", tagPath, uuid);
            return uuid;
        }
        logger.debug("Cache miss for '{}', cache size={}, keys={}", tagPath, tagPathToUUID.size(), tagPathToUUID.keySet());

        // Data type from the tag's metadata, else from the value; refuse to create without one
        String dataType = explicitDataType != null ? explicitDataType : dataTypeFor(tagPath, value);
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

            // The cache only knows what Factry had at the last refresh. Ask Factry once more
            // before creating: CreateMeasurements with a name that already exists does not return
            // the existing measurement but adds a duplicate in status Discovered.
            uuid = fetchSingleMeasurementUUID(tagPath, grpcClient);
            if (uuid != null) {
                logger.debug("Measurement '{}' already exists in Factry: {}", tagPath, uuid);
                pendingMetadata.remove(tagPath);
                return uuid;
            }

            logger.debug("Creating measurement for '{}' with dataType={}", tagPath, dataType);

            CreateMeasurement createMeasurement = buildCreateMeasurement(
                    tagPath, dataType, takeMetadata(tagPath, value));

            CreateMeasurementsRequest request = CreateMeasurementsRequest.newBuilder()
                    .addMeasurements(createMeasurement)
                    .build();

            grpcClient.createMeasurements(request);

            // Poll until the measurement becomes visible in Factry. This runs on the
            // store-and-forward thread, so keep it cheap: use a targeted single-measurement
            // lookup (keyword filter) instead of a full cache reload, and a tight fixed
            // backoff. This scales with the number of new tags, not the total measurement
            // count. Worst case is about 3 s of sleeps (the old loop slept 7.5 s), plus up
            // to the lookup deadline per poll when Factry is slow.
            long[] backoffMs = {100L, 200L, 400L, 800L, 1500L};
            for (int attempt = 0; attempt < backoffMs.length; attempt++) {
                try {
                    Thread.sleep(backoffMs[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }

                uuid = fetchSingleMeasurementUUID(tagPath, grpcClient);
                if (uuid != null) {
                    logger.debug("Measurement UUID resolved for '{}' on attempt {}: {}", tagPath, attempt + 1, uuid);
                    return uuid;
                }
                logger.debug("Measurement '{}' not visible yet, attempt {}/{}", tagPath, attempt + 1, backoffMs.length);
            }

            // Fallback: one full refresh in case the keyword filter never surfaced it
            // (e.g. unusual characters in the path defeat the server-side keyword match).
            refresh(grpcClient);
            uuid = tagPathToUUID.get(tagPath);
            if (uuid != null) {
                logger.debug("Measurement UUID resolved for '{}' via fallback refresh: {}", tagPath, uuid);
                return uuid;
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
     * Resolve UUIDs for a batch of tag paths.
     *
     * All paths already in the cache are returned immediately. For unknown paths not
     * being created by another thread, Factry is first asked for each exact name (one
     * lookup per path); the ones it does not have are batched into a single
     * {@code CreateMeasurementsRequest}; then all missing paths (newly created plus
     * those delegated to another thread) are polled together using targeted keyword
     * lookups. This keeps the store-and-forward thread's cost proportional to the
     * number of distinct new tags in the flush, not the number of points.
     *
     * @param tagPathToValue map from tag path to a representative value (used only for
     *                       data-type inference — the actual written values come from
     *                       the caller)
     * @return map from tag path to UUID; absent entries mean creation failed (caller
     *         should mark those points for S&amp;F retry)
     */
    public Map<String, String> batchGetOrCreateUUIDs(
            Map<String, Object> tagPathToValue, FactryGrpcClient grpcClient) {

        Map<String, String> result = new HashMap<>();

        // Split into cache hits and paths that need a UUID.
        Map<String, String> pathToDataType = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : tagPathToValue.entrySet()) {
            String path = entry.getKey();
            String uuid = tagPathToUUID.get(path);
            if (uuid != null) {
                result.put(path, uuid);
            } else {
                String dataType = dataTypeFor(path, entry.getValue());
                if (dataType != null) {
                    pathToDataType.put(path, dataType);
                }
                // No data type → no entry in result; the caller defers the point for S&F retry.
            }
        }

        if (pathToDataType.isEmpty()) {
            return result;
        }

        // Separate paths this call will create from ones another thread is already
        // handling (pendingCreations guards against duplicate-creation races).
        List<String> toCreate = new ArrayList<>();
        List<String> alreadyPending = new ArrayList<>();
        try {
            for (String path : pathToDataType.keySet()) {
                if (pendingCreations.add(path)) {
                    // Track the slot in toCreate right away so the finally below releases it
                    // even if the lookup throws.
                    toCreate.add(path);
                    // Double-check the cache after acquiring the creation slot, then Factry itself:
                    // creating a name that already exists adds a Discovered duplicate.
                    String uuid = tagPathToUUID.get(path);
                    if (uuid == null) {
                        uuid = fetchSingleMeasurementUUID(path, grpcClient);
                    }
                    if (uuid != null) {
                        result.put(path, uuid);
                        pendingMetadata.remove(path);
                        toCreate.remove(path);
                        pendingCreations.remove(path);
                    }
                } else {
                    alreadyPending.add(path);
                }
            }

            // One CreateMeasurementsRequest for all new paths.
            if (!toCreate.isEmpty()) {
                CreateMeasurementsRequest.Builder req = CreateMeasurementsRequest.newBuilder();
                for (String path : toCreate) {
                    req.addMeasurements(buildCreateMeasurement(
                            path, pathToDataType.get(path), takeMetadata(path, tagPathToValue.get(path))));
                }
                grpcClient.createMeasurements(req.build());
                logger.info("Batch-created {} measurements in one gRPC call", toCreate.size());
            }

            // Poll for all missing paths together (newly created + delegated).
            Set<String> stillMissing = new HashSet<>(toCreate);
            stillMissing.addAll(alreadyPending);

            long[] backoffMs = {100L, 200L, 400L, 800L, 1500L};
            for (int attempt = 0; attempt < backoffMs.length && !stillMissing.isEmpty(); attempt++) {
                try {
                    Thread.sleep(backoffMs[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                stillMissing.removeIf(path -> {
                    String uuid = fetchSingleMeasurementUUID(path, grpcClient);
                    if (uuid != null) {
                        result.put(path, uuid);
                        return true;
                    }
                    return false;
                });
            }

            // Fallback: full refresh for anything still not visible.
            if (!stillMissing.isEmpty()) {
                logger.warn("Batch: {} paths not visible after retries, triggering full refresh",
                        stillMissing.size());
                refresh(grpcClient);
                for (String path : stillMissing) {
                    String uuid = tagPathToUUID.get(path);
                    if (uuid != null) {
                        result.put(path, uuid);
                    } else {
                        logger.warn("Measurement not found after batch create + full refresh: '{}'", path);
                    }
                }
            }
        } catch (Exception e) {
            logger.error("Batch measurement creation failed", e);
        } finally {
            toCreate.forEach(pendingCreations::remove);
        }

        return result;
    }

    /**
     * Targeted lookup of a single measurement by its exact name, used before creating a
     * measurement (to reuse one Factry already has) and while polling for a just-created
     * measurement to become visible. Uses the server-side keyword filter with a small page,
     * bounded by the write deadline, instead of reloading the entire cache (measurements,
     * collectors, assets, asset properties), so a burst of new tags doesn't trigger repeated
     * full reloads on the store-and-forward thread. On a hit the entry is inserted into the
     * live maps so later lookups hit the fast path. Returns null if no active measurement with
     * that name is visible or the lookup fails.
     */
    private String fetchSingleMeasurementUUID(String tagPath, FactryGrpcClient grpcClient) {
        try {
            GetMeasurementsByFilterRequest request = GetMeasurementsByFilterRequest.newBuilder()
                    .setKeyword(tagPath)
                    .setPagination(Pagination.newBuilder().setLimit(50).setOffset(0).build())
                    .build();
            Measurements response = grpcClient.getMeasurementsByFilterWithDeadline(request);
            for (Measurement m : response.getMeasurementsList()) {
                // Keyword search is fuzzy/substring, so match the exact name ourselves.
                if (tagPath.equals(m.getName()) && "active".equalsIgnoreCase(m.getStatus())) {
                    tagPathToUUID.put(m.getName(), m.getUuid());
                    uuidToMeasurement.put(m.getUuid(), m);
                    return m.getUuid();
                }
            }
        } catch (Exception e) {
            logger.debug("Targeted lookup for '{}' failed: {}", tagPath, e.getMessage());
        }
        return null;
    }

    /**
     * Remove a measurement from the cache by UUID. The next {@link #getOrCreateUUID} call for
     * its tag path looks the measurement up in Factry first and only re-creates it if Factry
     * no longer has it.
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

    public String getCollectorName(String measurementUUID) {
        return measurementToCollectorName.get(measurementUUID);
    }

    public List<AssetProperty> getPropertiesForAsset(String assetUUID) {
        return assetProperties.getOrDefault(assetUUID, Collections.emptyList());
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

    /**
     * Take the cached metadata for a tag that is about to get a measurement. A Date value without a
     * known Ignition data type (for example one stored by a script) still marks the tag as DateTime,
     * so the read side can return Dates.
     */
    private Map<String, String> takeMetadata(String tagPath, Object value) {
        Map<String, String> metadata = pendingMetadata.remove(tagPath);
        if (value instanceof java.util.Date && (metadata == null || !metadata.containsKey(IGNITION_DATA_TYPE))) {
            metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
            metadata.put(IGNITION_DATA_TYPE, "DateTime");
        }
        return metadata;
    }

    /** Ignition tag property that holds the tag's data type (e.g. "DateTime"). */
    static final String IGNITION_DATA_TYPE = "dataType";

    /**
     * Measurement setting that keeps the tag's Ignition data type. Factry stores dates as plain
     * numbers, so this is how the read side knows to return Dates. Settings are used because the
     * gRPC read calls return them, while they leave the metadata map empty (Factry v8.2.3).
     */
    static final String IGNITION_DATA_TYPE_SETTING = "ignitionDataType";

    /** The Ignition data type recorded on a measurement at creation, or null when there is none. */
    static String ignitionDataType(Measurement m) {
        Value v = m.getSettings().getFieldsMap().get(IGNITION_DATA_TYPE_SETTING);
        return v != null && v.getKindCase() == Value.KindCase.STRING_VALUE ? v.getStringValue() : null;
    }

    /** Factry derives EngineeringSpecs from a nested "Config" attribute group with these keys. */
    private static final String CONFIG_GROUP = "Config";

    /** Ignition tag property -> Factry Config attribute key, for the string-valued specs. */
    private static final Map<String, String> CONFIG_STRING_KEYS = Map.of("engUnit", "UoM");

    /** Ignition tag property -> Factry Config attribute key, for the number-valued specs. */
    private static final Map<String, String> CONFIG_NUMBER_KEYS = Map.of(
            "engLow", "ValueMin",
            "engHigh", "ValueMax");

    /**
     * Build the CreateMeasurement for a new tag, mapping the cached Ignition metadata onto the
     * shape Factry expects.
     *
     * Factry does not store engineering specs as a field. The server derives EngineeringSpecs from
     * a nested "Config" attribute group (UoM, ValueMin, ValueMax, LimitLo, LimitHi), the same shape
     * the Excel measurement importer writes. So the known Ignition properties go into that group,
     * numeric ones as numbers. LimitLo and LimitHi have no plain Ignition tag property equivalent
     * (those values live in alarm setpoints) and stay unset.
     *
     * Every other property goes to the metadata map, which stays the channel for arbitrary extras.
     * Factry stores it and returns it over REST, but on Factry Historian v8.2.3 and v8.2.4 the gRPC
     * read calls (GetMeasurements, GetMeasurementsByFilter) leave Measurement.metadata empty, so
     * these extras do not come back to Ignition. The read side still merges the map in case Factry
     * starts returning it; Config-derived engineeringSpecs wins over it.
     *
     * The tag's data type is also copied to the measurement settings, which the gRPC read calls do
     * return (see {@link #IGNITION_DATA_TYPE_SETTING}).
     */
    static CreateMeasurement buildCreateMeasurement(String tagPath, String dataType,
                                                    Map<String, String> metadata) {
        CreateMeasurement.Builder builder = CreateMeasurement.newBuilder()
                .setName(tagPath)
                .setAutoOnboard(true)
                .setDataType(dataType);

        if (metadata == null || metadata.isEmpty()) {
            return builder.build();
        }

        String ignitionType = metadata.get(IGNITION_DATA_TYPE);
        if (ignitionType != null) {
            builder.setSettings(Struct.newBuilder()
                    .putFields(IGNITION_DATA_TYPE_SETTING, Value.newBuilder().setStringValue(ignitionType).build()));
        }

        Struct.Builder config = Struct.newBuilder();
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            String name = entry.getKey();
            String value = entry.getValue();
            if (value == null) {
                continue;
            }

            if ("description".equals(name)) {
                builder.setDescription(value);
                continue;
            }

            String stringKey = CONFIG_STRING_KEYS.get(name);
            if (stringKey != null) {
                config.putFields(stringKey, Value.newBuilder().setStringValue(value).build());
                continue;
            }

            String numberKey = CONFIG_NUMBER_KEYS.get(name);
            if (numberKey != null) {
                try {
                    config.putFields(numberKey, Value.newBuilder()
                            .setNumberValue(Double.parseDouble(value.trim()))
                            .build());
                    continue;
                } catch (NumberFormatException nfe) {
                    logger.debug("Property '{}' of '{}' is not numeric ('{}'), keeping it in metadata",
                            name, tagPath, value);
                }
            }

            builder.putMetadata(name, MetadataProperty.newBuilder()
                    .setDataType(MetadataProperty.DataType.STRING)
                    .setValue(Value.newBuilder().setStringValue(value).build())
                    .build());
        }

        if (config.getFieldsCount() > 0) {
            builder.setAttributes(Struct.newBuilder()
                    .putFields(CONFIG_GROUP, Value.newBuilder()
                            .setStructValue(config.build())
                            .build()));
        }

        logger.debug("Applied cached metadata to new measurement '{}'", tagPath);
        return builder.build();
    }

    /**
     * Factry data type for a new measurement. The tag's Ignition data type, from the metadata
     * Ignition sends before the first value, wins over the value: Store and Forward hands Boolean
     * values over as Long 0/1 (its storage format has no boolean type), so the value alone would
     * turn a Boolean tag into a number measurement.
     */
    String dataTypeFor(String tagPath, Object value) {
        Map<String, String> metadata = pendingMetadata.get(tagPath);
        String fromTag = metadata != null ? fromIgnitionDataType(metadata.get(IGNITION_DATA_TYPE)) : null;
        return fromTag != null ? fromTag : toFactryDataType(value);
    }

    /**
     * Map an Ignition DataType name to a Factry data type, or null when there is no mapping.
     * Array types map to their element type.
     */
    private static String fromIgnitionDataType(String ignitionType) {
        if (ignitionType == null) {
            return null;
        }
        String type = ignitionType.endsWith("Array")
                ? ignitionType.substring(0, ignitionType.length() - "Array".length())
                : ignitionType;
        if (type.equals("Boolean")) {
            return "boolean";
        }
        if (type.startsWith("Int") || type.startsWith("Float") || type.equals("DateTime")) {
            return "number";
        }
        if (type.equals("String") || type.equals("Text")) {
            return "string";
        }
        return null;
    }

    static String toFactryDataType(Object value) {
        if (value instanceof Boolean) {
            return "boolean";
        } else if (value instanceof Number || value instanceof java.util.Date) {
            // Dates are stored as epoch milliseconds, see FactryStorageEngine.toProtoValue.
            return "number";
        } else if (value instanceof String) {
            return "string";
        }
        return null;
    }
}
