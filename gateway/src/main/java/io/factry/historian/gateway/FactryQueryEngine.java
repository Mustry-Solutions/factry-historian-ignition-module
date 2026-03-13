package io.factry.historian.gateway;

import com.inductiveautomation.historian.common.model.TimeRange;
import com.inductiveautomation.historian.common.model.data.AtomicPoint;
import com.inductiveautomation.historian.common.model.data.DataPointFactory;
import com.inductiveautomation.historian.common.model.data.DataPointType;
import com.inductiveautomation.historian.common.model.options.RawQueryKey;
import com.inductiveautomation.historian.common.model.options.RawQueryOptions;
import com.inductiveautomation.historian.gateway.api.query.AbstractQueryEngine;
import com.inductiveautomation.historian.gateway.api.query.HistoricalNode;
import com.inductiveautomation.historian.gateway.api.query.browsing.BrowsePublisher;
import com.inductiveautomation.historian.gateway.api.query.processor.DefaultProcessingContext;
import com.inductiveautomation.historian.gateway.api.query.processor.ProcessingContext;
import com.inductiveautomation.historian.gateway.api.query.processor.RawPointProcessor;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.browsing.BrowseFilter;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import io.factry.historian.proto.Asset;
import io.factry.historian.proto.Calculation;
import io.factry.historian.proto.Measurement;
import io.factry.historian.proto.MeasurementPoints;
import io.factry.historian.proto.QueryPointsReply;
import io.factry.historian.proto.QueryRawPointsRequest;

import com.google.protobuf.util.Timestamps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class FactryQueryEngine extends AbstractQueryEngine {
    private final FactryHistorianSettings settings;
    private final FactryGrpcClient grpcClient;
    private final MeasurementCache measurementCache;

    public FactryQueryEngine(
        GatewayContext context,
        String historianName,
        FactryHistorianSettings settings,
        FactryGrpcClient grpcClient,
        MeasurementCache measurementCache
    ) {
        super(context, historianName, LoggerEx.newBuilder().build(FactryQueryEngine.class));
        this.settings = settings;
        this.grpcClient = grpcClient;
        this.measurementCache = measurementCache;
        logger.info("Factry Query Engine initialized");
    }

    @Override
    protected void doBrowse(QualifiedPath root, BrowseFilter filter, BrowsePublisher publisher) {
        try {
            measurementCache.refresh(grpcClient);

            // Extract browse prefix from root (AdaptedQualifiedPath)
            String prefix = extractBrowsePrefix(root);

            logger.info("Browse: prefix='" + prefix + "'");

            String category = TagPathUtil.extractCategory(prefix);

            if (prefix.isEmpty()) {
                // Root level: show three category folders
                publisher.newNode("folder", TagPathUtil.CATEGORY_MEASUREMENTS).hasChildren(true).add();
                publisher.newNode("folder", TagPathUtil.CATEGORY_CALCULATIONS).hasChildren(true).add();
                publisher.newNode("folder", TagPathUtil.CATEGORY_ASSETS).hasChildren(true).add();
                logger.info("Browse: published 3 category folders at root");
                return;
            }

            if (TagPathUtil.CATEGORY_MEASUREMENTS.equals(category)) {
                // Measurements: existing hierarchical browse with sys/prov/tag structure
                String measPrefix = TagPathUtil.stripCategory(prefix);
                if (!measPrefix.isEmpty() && !measPrefix.endsWith("/")) {
                    measPrefix += "/";
                }
                browsePaths(collectMeasurementDisplayPaths(), measPrefix, publisher);
            } else if (TagPathUtil.CATEGORY_CALCULATIONS.equals(category)) {
                // Calculations: flat list of names
                String calcPrefix = TagPathUtil.stripCategory(prefix);
                if (!calcPrefix.isEmpty() && !calcPrefix.endsWith("/")) {
                    calcPrefix += "/";
                }
                List<String> calcNames = new ArrayList<>();
                for (Calculation c : measurementCache.getAllCalculations()) {
                    calcNames.add(c.getName());
                }
                browsePaths(calcNames, calcPrefix, publisher);
            } else if (TagPathUtil.CATEGORY_ASSETS.equals(category)) {
                // Assets: hierarchical by "/" in name
                String assetPrefix = TagPathUtil.stripCategory(prefix);
                if (!assetPrefix.isEmpty() && !assetPrefix.endsWith("/")) {
                    assetPrefix += "/";
                }
                List<String> assetNames = new ArrayList<>();
                for (Asset a : measurementCache.getAllAssets()) {
                    assetNames.add(a.getName());
                }
                browsePaths(assetNames, assetPrefix, publisher);
            } else {
                // Legacy fallback: browse measurements without category prefix
                browsePaths(collectMeasurementDisplayPaths(), prefix, publisher);
            }

        } catch (Exception e) {
            logger.error("Error browsing", e);
        }
    }

    private List<String> collectMeasurementDisplayPaths() {
        List<String> displayPaths = new ArrayList<>();
        for (Measurement m : measurementCache.getAllMeasurements()) {
            displayPaths.add(TagPathUtil.storedPathToDisplayPath(m.getName()));
        }
        return displayPaths;
    }

    private void browsePaths(List<String> paths, String prefix, BrowsePublisher publisher) {
        Set<String> folders = new LinkedHashSet<>();
        List<String> leafTags = new ArrayList<>();

        for (String path : paths) {
            if (!path.startsWith(prefix)) {
                continue;
            }
            String remaining = path.substring(prefix.length());
            int slashPos = remaining.indexOf('/');
            if (slashPos >= 0) {
                folders.add(remaining.substring(0, slashPos));
            } else if (!remaining.isEmpty()) {
                leafTags.add(remaining);
            }
        }

        for (String folder : folders) {
            publisher.newNode("folder", folder).hasChildren(true).add();
        }
        for (String leaf : leafTags) {
            publisher.newNode("tag", leaf).hasChildren(false).add();
        }

        logger.info("Browse: published " + folders.size() + " folders, " + leafTags.size() + " tags");
    }

    /**
     * Extract the browse prefix from the root QualifiedPath.
     * Uses AdaptedQualifiedPath.getOriginalPath() via reflection to access
     * the full path including folder: components.
     */
    private String extractBrowsePrefix(QualifiedPath root) {
        if (root == null) {
            return "";
        }

        try {
            Object original = root.getClass().getMethod("getOriginalPath").invoke(root);
            if (original != null) {
                String fullPath = original.toString();
                if (fullPath != null && !fullPath.isEmpty()) {
                    return TagPathUtil.parseFolderPrefix(fullPath);
                }
            }
        } catch (Exception e) {
            logger.debug("getOriginalPath() not available: " + e.getMessage());
        }

        String rootStr = root.toString();
        if (rootStr != null && !rootStr.isEmpty()) {
            return TagPathUtil.parseFolderPrefix(rootStr);
        }

        return "";
    }

    @Override
    protected Optional<Integer> doQueryRaw(RawQueryOptions options, RawPointProcessor processor) {
        logger.info("Raw query request: " + options);

        try {
            // Build the processing context and initialize
            ProcessingContext<RawQueryKey, DataPointType> context =
                    DefaultProcessingContext.<RawQueryKey, DataPointType>builder().build();
            if (!processor.onInitialize(context)) {
                logger.warn("Processor rejected initialization");
                return Optional.of(0);
            }

            // Map query keys to measurement UUIDs
            var queryKeys = options.getQueryKeys();
            List<String> measurementUUIDs = new ArrayList<>();
            Map<String, RawQueryKey> uuidToKeyMap = new HashMap<>();

            for (RawQueryKey key : queryKeys) {
                QualifiedPath source = key.source();
                String tagPath = toStoredTagPath(source);
                String uuid = lookupUUID(tagPath);

                if (uuid != null) {
                    measurementUUIDs.add(uuid);
                    uuidToKeyMap.put(uuid, key);
                    if (settings.isDebugLogging()) {
                        logger.debug("Mapped " + source + " -> " + tagPath + " -> " + uuid);
                    }
                } else {
                    logger.warn("No measurement UUID found for tag path: " + tagPath);
                    processor.onKeyFailure(key, QualityCode.Bad_NotFound);
                }
            }

            if (measurementUUIDs.isEmpty()) {
                processor.onComplete();
                return Optional.of(0);
            }

            // Build gRPC request
            QueryRawPointsRequest.Builder reqBuilder = QueryRawPointsRequest.newBuilder()
                    .addAllMeasurementUUIDs(measurementUUIDs);

            options.getTimeRange().ifPresent(tr -> {
                reqBuilder.setStartTime(Timestamps.fromMillis(tr.startTime().toEpochMilli()));
                reqBuilder.setEndTime(Timestamps.fromMillis(tr.endTime().toEpochMilli()));
            });

            QueryPointsReply reply = grpcClient.queryRawPoints(reqBuilder.build());

            int totalPoints = 0;
            for (MeasurementPoints mp : reply.getMeasurementPointsList()) {
                RawQueryKey key = uuidToKeyMap.get(mp.getMeasurementUUID());
                if (key == null) {
                    continue;
                }

                QualifiedPath path = key.source();

                for (var pt : mp.getPointsList()) {
                    Instant timestamp = Instant.ofEpochSecond(
                            pt.getTimestamp().getSeconds(),
                            pt.getTimestamp().getNanos()
                    );

                    QualityCode quality = statusToQuality(pt.getStatus());
                    Object value = protoValueToJava(pt.getValue());

                    AtomicPoint<?> atomicPoint = DataPointFactory.createAtomicPoint(
                            value, quality, timestamp, path);

                    if (!processor.onPointAvailable(key, atomicPoint)) {
                        processor.onComplete();
                        return Optional.of(totalPoints);
                    }
                    totalPoints++;
                }
            }

            processor.onComplete();
            logger.info("Query completed with " + totalPoints + " total points");
            return Optional.of(totalPoints);

        } catch (Exception e) {
            logger.error("Error querying raw data", e);
            processor.onError(e);
            return Optional.empty();
        }
    }

    @Override
    protected Optional<? extends HistoricalNode> lookupNode(QualifiedPath path) {
        logger.debug("Looking up node: " + path);
        String tagPath = toStoredTagPath(path);
        return findNode(tagPath, path);
    }

    @Override
    protected Map<QualifiedPath, ? extends HistoricalNode> queryForHistoricalNodes(
        Set<QualifiedPath> paths,
        TimeRange timeRange
    ) {
        logger.debug("Query for historical nodes: " + paths.size() + " paths");
        Map<QualifiedPath, HistoricalNode> result = new HashMap<>();
        for (QualifiedPath path : paths) {
            String tagPath = toStoredTagPath(path);
            findNode(tagPath, path).ifPresent(node -> result.put(path, node));
        }
        return result;
    }

    @Override
    protected boolean isEngineUnavailable() {
        return false;
    }

    private Optional<FactryHistoricalNode> findNode(String tagPath, QualifiedPath path) {
        // Try measurement
        Measurement m = measurementCache.getMeasurementByName(tagPath);
        if (m == null) {
            measurementCache.refresh(grpcClient);
            m = measurementCache.getMeasurementByName(tagPath);
        }
        if (m != null) {
            Instant createdTime = m.hasCreatedAt()
                    ? Instant.ofEpochSecond(m.getCreatedAt().getSeconds(), m.getCreatedAt().getNanos())
                    : Instant.EPOCH;
            return Optional.of(new FactryHistoricalNode(m.getUuid(), path, m.getDatatype(), createdTime));
        }

        // Try calculation
        Calculation c = measurementCache.getCalculationByName(tagPath);
        if (c != null) {
            Instant createdTime = c.hasCreatedAt()
                    ? Instant.ofEpochSecond(c.getCreatedAt().getSeconds(), c.getCreatedAt().getNanos())
                    : Instant.EPOCH;
            return Optional.of(new FactryHistoricalNode(c.getUuid(), path, c.getDatatype(), createdTime));
        }

        // Try asset
        Asset a = measurementCache.getAssetByName(tagPath);
        if (a != null) {
            Instant createdTime = a.hasCreatedAt()
                    ? Instant.ofEpochSecond(a.getCreatedAt().getSeconds(), a.getCreatedAt().getNanos())
                    : Instant.EPOCH;
            return Optional.of(new FactryHistoricalNode(a.getUuid(), path, a.getDatatype(), createdTime));
        }

        return Optional.empty();
    }

    private String lookupUUID(String tagPath) {
        // Try measurement
        String uuid = measurementCache.getUUID(tagPath);
        if (uuid != null) return uuid;

        // Try calculation
        uuid = measurementCache.getCalculationUUID(tagPath);
        if (uuid != null) return uuid;

        // Try asset
        uuid = measurementCache.getAssetUUID(tagPath);
        if (uuid != null) return uuid;

        // Refresh and retry
        measurementCache.refresh(grpcClient);
        uuid = measurementCache.getUUID(tagPath);
        if (uuid != null) return uuid;
        uuid = measurementCache.getCalculationUUID(tagPath);
        if (uuid != null) return uuid;
        uuid = measurementCache.getAssetUUID(tagPath);
        return uuid;
    }

    private String toStoredTagPath(QualifiedPath path) {
        return TagPathUtil.qualifiedPathToStoredPath(path.toString());
    }

    private static QualityCode statusToQuality(String status) {
        if (status == null) {
            return QualityCode.Good;
        }
        switch (status) {
            case "Good":
                return QualityCode.Good;
            case "Uncertain":
                return QualityCode.Uncertain;
            case "Bad":
                return QualityCode.Bad;
            default:
                return QualityCode.Good;
        }
    }

    private static Object protoValueToJava(com.google.protobuf.Value value) {
        if (value == null) {
            return null;
        }
        switch (value.getKindCase()) {
            case BOOL_VALUE:
                return value.getBoolValue();
            case NUMBER_VALUE:
                return value.getNumberValue();
            case STRING_VALUE:
                return value.getStringValue();
            default:
                return null;
        }
    }
}
