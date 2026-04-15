package io.factry.historian.gateway;

import com.inductiveautomation.historian.common.model.TimeRange;
import com.inductiveautomation.historian.common.model.AggregationType;
import com.inductiveautomation.historian.common.model.LegacyAggregateAdapter;
import com.inductiveautomation.historian.common.model.TimeWindow;
import com.inductiveautomation.historian.common.model.data.AggregatedDataPoint;
import com.inductiveautomation.historian.common.model.data.AtomicPoint;
import com.inductiveautomation.historian.common.model.data.DataPointFactory;
import com.inductiveautomation.historian.common.model.data.DataPointType;
import com.inductiveautomation.historian.common.model.data.MetadataPoint;
import com.inductiveautomation.historian.common.model.options.AggregatedQueryKey;
import com.inductiveautomation.historian.common.model.options.AggregatedQueryOptions;
import com.inductiveautomation.historian.common.model.options.MetadataQueryKey;
import com.inductiveautomation.historian.common.model.options.MetadataQueryOptions;
import com.inductiveautomation.historian.common.model.options.RawQueryKey;
import com.inductiveautomation.historian.common.model.options.RawQueryOptions;
import com.inductiveautomation.historian.gateway.api.query.AbstractQueryEngine;
import com.inductiveautomation.historian.gateway.api.query.HistoricalNode;
import com.inductiveautomation.historian.gateway.api.query.browsing.BrowsePublisher;
import com.inductiveautomation.historian.gateway.api.query.processor.AggregatedPointProcessor;
import com.inductiveautomation.historian.gateway.api.query.processor.DefaultProcessingContext;
import com.inductiveautomation.historian.gateway.api.query.processor.MetadataPointProcessor;
import com.inductiveautomation.historian.gateway.api.query.processor.ProcessingContext;
import com.inductiveautomation.historian.gateway.api.query.processor.RawPointProcessor;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.StringPath;
import com.inductiveautomation.ignition.common.browsing.BrowseFilter;
import com.inductiveautomation.ignition.common.config.BasicProperty;
import com.inductiveautomation.ignition.common.config.BasicPropertySet;
import com.inductiveautomation.ignition.common.config.PropertySet;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.history.AggregationMode;
import com.inductiveautomation.ignition.common.util.LoggerEx;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;

import io.factry.historian.proto.Aggregation;
import io.factry.historian.proto.Asset;
import io.factry.historian.proto.Measurement;
import io.factry.historian.proto.QueryTimeseriesRequest;
import io.factry.historian.proto.QueryTimeseriesResponse;
import io.factry.historian.proto.Series;
import io.factry.historian.proto.SeriesPoint;

import com.google.protobuf.util.Timestamps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class FactryQueryEngine extends AbstractQueryEngine {

    private static final List<AggregationType> SUPPORTED_AGGREGATES = List.of(
            LegacyAggregateAdapter.of(AggregationMode.Average),
            LegacyAggregateAdapter.of(AggregationMode.SimpleAverage),
            LegacyAggregateAdapter.of(AggregationMode.MinMax),
            LegacyAggregateAdapter.of(AggregationMode.Minimum),
            LegacyAggregateAdapter.of(AggregationMode.Maximum),
            LegacyAggregateAdapter.of(AggregationMode.Sum),
            LegacyAggregateAdapter.of(AggregationMode.Count),
            LegacyAggregateAdapter.of(AggregationMode.LastValue),
            LegacyAggregateAdapter.of(AggregationMode.Range),
            LegacyAggregateAdapter.of(AggregationMode.Variance),
            LegacyAggregateAdapter.of(AggregationMode.StdDev)
    );

    private volatile FactryHistorianSettings settings;
    private final FactryGrpcClient grpcClient;
    private final MeasurementCache measurementCache;
    private final HistorianMetrics metrics;

    public FactryQueryEngine(
        GatewayContext context,
        String historianName,
        FactryHistorianSettings settings,
        FactryGrpcClient grpcClient,
        MeasurementCache measurementCache,
        HistorianMetrics metrics
    ) {
        super(context, historianName, LoggerEx.newBuilder().build(FactryQueryEngine.class));
        this.settings = settings;
        this.grpcClient = grpcClient;
        this.measurementCache = measurementCache;
        this.metrics = metrics;
        logger.debug("Factry Query Engine initialized");
    }

    @Override
    protected void doBrowse(QualifiedPath root, BrowseFilter filter, BrowsePublisher publisher) {
        try {
            measurementCache.refresh(grpcClient);

            // Extract browse prefix from root (AdaptedQualifiedPath)
            String prefix = extractBrowsePrefix(root);

            logger.debug("Browse: prefix='" + prefix + "'");

            String category = TagPathUtil.extractCategory(prefix);

            if (prefix.isEmpty()) {
                // Root level: show two category folders
                publisher.newNode("folder", TagPathUtil.CATEGORY_MEASUREMENTS).hasChildren(true).add();
                publisher.newNode("folder", TagPathUtil.CATEGORY_ASSETS).hasChildren(true).add();
                logger.debug("Browse: published 2 category folders at root");
                return;
            }

            if (TagPathUtil.CATEGORY_MEASUREMENTS.equals(category)) {
                // Measurements: existing hierarchical browse with sys/prov/tag structure
                String measPrefix = TagPathUtil.stripCategory(prefix);
                if (!measPrefix.isEmpty() && !measPrefix.endsWith("/")) {
                    measPrefix += "/";
                }
                browsePaths(collectMeasurementDisplayToStoredMap(), measPrefix, publisher);
            } else if (TagPathUtil.CATEGORY_ASSETS.equals(category)) {
                // Assets: hierarchical by "/" in name
                String assetPrefix = TagPathUtil.stripCategory(prefix);
                if (!assetPrefix.isEmpty() && !assetPrefix.endsWith("/")) {
                    assetPrefix += "/";
                }
                Map<String, String> assetDisplayToStored = new HashMap<>();
                for (Asset a : measurementCache.getAllAssets()) {
                    assetDisplayToStored.put(a.getName(), a.getName());
                }
                browsePaths(assetDisplayToStored, assetPrefix, publisher);
            } else {
                // Legacy fallback: browse measurements without category prefix
                browsePaths(collectMeasurementDisplayToStoredMap(), prefix, publisher);
            }

        } catch (Exception e) {
            logger.error("Error browsing historian at path '" + root + "'", e);
        }
    }

    private Map<String, String> collectMeasurementDisplayToStoredMap() {
        Map<String, String> displayToStored = new HashMap<>();
        for (Measurement m : measurementCache.getAllMeasurements()) {
            String display = TagPathUtil.storedPathToDisplayPath(m.getName());
            displayToStored.put(display, m.getName());
        }
        return displayToStored;
    }

    private void browsePaths(Map<String, String> displayToStored, String prefix, BrowsePublisher publisher) {
        Set<String> folders = new LinkedHashSet<>();
        // Map from leaf display name to full display path (for stored path lookup)
        Map<String, String> leafDisplayNameToFullPath = new HashMap<>();

        for (String path : displayToStored.keySet()) {
            if (!path.startsWith(prefix)) {
                continue;
            }
            String remaining = path.substring(prefix.length());
            int slashPos = remaining.indexOf('/');
            if (slashPos >= 0) {
                folders.add(remaining.substring(0, slashPos));
            } else if (!remaining.isEmpty()) {
                leafDisplayNameToFullPath.put(remaining, path);
            }
        }

        for (String folder : folders) {
            publisher.newNode("folder", folder).hasChildren(true).add();
        }
        for (Map.Entry<String, String> leaf : leafDisplayNameToFullPath.entrySet()) {
            String displayName = leaf.getKey();
            String storedPath = displayToStored.get(leaf.getValue());
            // Use stored path as tag identifier so queries can resolve it back;
            // displayPath controls what appears in the UI.
            publisher.newNode("tag", storedPath != null ? storedPath : displayName)
                    .displayPath(StringPath.of(displayName))
                    .hasChildren(false)
                    .add();
        }

        logger.debug("Browse: published " + folders.size() + " folders, " + leafDisplayNameToFullPath.size() + " tags");
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
        logger.debug("Raw query request: " + options);
        long startMs = System.currentTimeMillis();

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

            // Build gRPC request using QueryTimeseries (no aggregation = raw)
            QueryTimeseriesRequest.Builder reqBuilder = QueryTimeseriesRequest.newBuilder()
                    .addAllMeasurementUUIDs(measurementUUIDs);

            options.getTimeRange().ifPresent(tr -> {
                reqBuilder.setStart(Timestamps.fromMillis(tr.startTime().toEpochMilli()));
                reqBuilder.setEnd(Timestamps.fromMillis(tr.endTime().toEpochMilli()));
            });

            QueryTimeseriesResponse reply = grpcClient.queryTimeseries(reqBuilder.build());

            int totalPoints = 0;
            for (Series series : reply.getSeriesList()) {
                String uuid = series.hasMeasurementUUID() ? series.getMeasurementUUID() : "";
                RawQueryKey key = uuidToKeyMap.get(uuid);
                if (key == null) {
                    continue;
                }

                QualifiedPath path = key.source();

                for (SeriesPoint pt : series.getDataPointsList()) {
                    Instant timestamp = Instant.ofEpochMilli(pt.getTimestamp());
                    Object value = protoValueToJava(pt.getValue());

                    AtomicPoint<?> atomicPoint = DataPointFactory.createAtomicPoint(
                            value, QualityCode.Good, timestamp, path);

                    if (!processor.onPointAvailable(key, atomicPoint)) {
                        processor.onComplete();
                        return Optional.of(totalPoints);
                    }
                    totalPoints++;
                }
            }

            processor.onComplete();
            metrics.recordRawQuery(totalPoints, System.currentTimeMillis() - startMs);
            logger.debug("Query completed with " + totalPoints + " total points");
            return Optional.of(totalPoints);

        } catch (Exception e) {
            logger.error("Error querying raw data", e);
            processor.onError(e);
            return Optional.empty();
        }
    }

    @Override
    public Collection<AggregationType> getNativeAggregates() {
        return SUPPORTED_AGGREGATES;
    }

    @Override
    protected Optional<Integer> doQueryAggregated(AggregatedQueryOptions options, AggregatedPointProcessor processor) {
        logger.debug("Aggregated query request: " + options);
        long startMs = System.currentTimeMillis();

        try {
            ProcessingContext context =
                    DefaultProcessingContext.builder().build();
            if (!processor.onInitialize(context)) {
                logger.warn("Processor rejected initialization for aggregated query");
                return Optional.of(0);
            }

            TimeWindow timeWindow = options.getTimeWindow();
            String period = timeWindowToPeriod(timeWindow);

            var queryKeys = options.getQueryKeys();
            int pointCount = 0;

            for (AggregatedQueryKey key : queryKeys) {
                QualifiedPath source = key.source();
                String tagPath = toStoredTagPath(source);
                String uuid = lookupUUID(tagPath);

                if (uuid == null) {
                    logger.warn("No measurement UUID found for aggregated query: " + tagPath);
                    processor.onKeyFailure(key, QualityCode.Bad_NotFound);
                    continue;
                }

                try {
                    String aggName = key.aggregationType().name();

                    if ("MinMax".equals(aggName)) {
                        // MinMax: query min and max separately, emit pairs
                        pointCount += queryMinMax(uuid, source, key, period, options, processor);
                    } else {
                        String factryFunction = toFactryAggregateFunction(aggName);
                        pointCount += querySingleAggregate(uuid, source, key, factryFunction, period, options, processor);
                    }
                } catch (Exception e) {
                    logger.error("Error querying aggregated data for: " + tagPath, e);
                    processor.onKeyFailure(key, QualityCode.Bad);
                }
            }

            processor.onComplete();
            metrics.recordAggregatedQuery(pointCount, System.currentTimeMillis() - startMs);
            logger.debug("Aggregated query completed with " + pointCount + " points");
            return Optional.of(pointCount);

        } catch (Exception e) {
            logger.error("Error in aggregated query", e);
            processor.onError(e);
            return Optional.empty();
        }
    }

    @Override
    protected Optional<Integer> doQueryMetadata(MetadataQueryOptions options, MetadataPointProcessor processor) {
        logger.debug("Metadata query request with " + options.getQueryKeys().size() + " keys");

        try {
            ProcessingContext context = DefaultProcessingContext.builder().build();
            if (!processor.onInitialize(context)) {
                logger.debug("Processor declined initialization for metadata query");
                processor.onComplete();
                return Optional.of(0);
            }

            int pointCount = 0;
            for (MetadataQueryKey key : options.getQueryKeys()) {
                String tagPath = toStoredTagPath(key.identifier());
                Measurement m = measurementCache.getMeasurementByName(tagPath);

                if (m == null) {
                    measurementCache.refresh(grpcClient);
                    m = measurementCache.getMeasurementByName(tagPath);
                }

                if (m == null) {
                    processor.onKeyFailure(key, QualityCode.Bad_NotFound);
                    continue;
                }

                PropertySet ps = measurementToPropertySet(m);
                MetadataPoint point = MetadataPoint.from(ps, key.identifier());
                processor.onPointAvailable(key, point);
                pointCount++;
            }

            processor.onComplete();
            return Optional.of(pointCount);

        } catch (Exception e) {
            logger.error("Error querying metadata", e);
            processor.onError(e);
            return Optional.empty();
        }
    }

    private static PropertySet measurementToPropertySet(Measurement m) {
        BasicPropertySet ps = new BasicPropertySet();

        if (m.getDatatype() != null && !m.getDatatype().isEmpty()) {
            ps.set(new BasicProperty<>("datatype", String.class), m.getDatatype());
        }
        if (m.getStatus() != null && !m.getStatus().isEmpty()) {
            ps.set(new BasicProperty<>("status", String.class), m.getStatus());
        }
        if (m.getName() != null && !m.getName().isEmpty()) {
            ps.set(new BasicProperty<>("name", String.class), m.getName());
        }

        if (m.hasEngineeringSpecs()) {
            var specs = m.getEngineeringSpecs();
            if (specs.hasUom()) {
                ps.set(new BasicProperty<>("engUnit", String.class), specs.getUom());
            }
            if (specs.hasValueMin()) {
                ps.set(new BasicProperty<>("engLow", Double.class), specs.getValueMin());
            }
            if (specs.hasValueMax()) {
                ps.set(new BasicProperty<>("engHigh", Double.class), specs.getValueMax());
            }
            if (specs.hasLimitLo()) {
                ps.set(new BasicProperty<>("limitLow", Double.class), specs.getLimitLo());
            }
            if (specs.hasLimitHi()) {
                ps.set(new BasicProperty<>("limitHigh", Double.class), specs.getLimitHi());
            }
        }

        return ps;
    }

    private int querySingleAggregate(
            String uuid, QualifiedPath source, AggregatedQueryKey key,
            String factryFunction, String period,
            AggregatedQueryOptions options, AggregatedPointProcessor processor) {

        Aggregation aggregation = Aggregation.newBuilder()
                .setName(factryFunction)
                .setPeriod(period)
                .setFillType("none")
                .build();

        QueryTimeseriesRequest.Builder reqBuilder = QueryTimeseriesRequest.newBuilder()
                .addMeasurementUUIDs(uuid)
                .setAggregation(aggregation);

        options.getTimeRange().ifPresent(tr -> {
            reqBuilder.setStart(Timestamps.fromMillis(tr.startTime().toEpochMilli()));
            reqBuilder.setEnd(Timestamps.fromMillis(tr.endTime().toEpochMilli()));
        });

        QueryTimeseriesResponse reply = grpcClient.queryTimeseries(reqBuilder.build());

        int count = 0;
        for (Series series : reply.getSeriesList()) {
            for (SeriesPoint pt : series.getDataPointsList()) {
                Instant timestamp = Instant.ofEpochMilli(pt.getTimestamp());
                Object value = protoValueToJava(pt.getValue());

                AggregatedDataPoint<Object, AggregationType> point =
                        AggregatedDataPoint.<Object, AggregationType>builder(source, key.aggregationType())
                                .value(value)
                                .quality(QualityCode.Good)
                                .timestamp(timestamp)
                                .build();

                processor.onPointAvailable(key, point);
                count++;
            }
        }
        return count;
    }

    private int queryMinMax(
            String uuid, QualifiedPath source, AggregatedQueryKey key,
            String period, AggregatedQueryOptions options,
            AggregatedPointProcessor processor) {

        // Query min and max separately
        QueryTimeseriesRequest.Builder minReqBuilder = QueryTimeseriesRequest.newBuilder()
                .addMeasurementUUIDs(uuid)
                .setAggregation(Aggregation.newBuilder().setName("min").setPeriod(period).setFillType("none").build());
        QueryTimeseriesRequest.Builder maxReqBuilder = QueryTimeseriesRequest.newBuilder()
                .addMeasurementUUIDs(uuid)
                .setAggregation(Aggregation.newBuilder().setName("max").setPeriod(period).setFillType("none").build());

        options.getTimeRange().ifPresent(tr -> {
            long startMs = tr.startTime().toEpochMilli();
            long endMs = tr.endTime().toEpochMilli();
            minReqBuilder.setStart(Timestamps.fromMillis(startMs)).setEnd(Timestamps.fromMillis(endMs));
            maxReqBuilder.setStart(Timestamps.fromMillis(startMs)).setEnd(Timestamps.fromMillis(endMs));
        });

        QueryTimeseriesResponse minReply = grpcClient.queryTimeseries(minReqBuilder.build());
        QueryTimeseriesResponse maxReply = grpcClient.queryTimeseries(maxReqBuilder.build());

        // Collect min values by timestamp
        Map<Long, Object> minValues = new HashMap<>();
        for (Series series : minReply.getSeriesList()) {
            for (SeriesPoint pt : series.getDataPointsList()) {
                minValues.put(pt.getTimestamp(), protoValueToJava(pt.getValue()));
            }
        }

        // Emit min/max pairs for each bucket
        int count = 0;
        for (Series series : maxReply.getSeriesList()) {
            for (SeriesPoint pt : series.getDataPointsList()) {
                long ts = pt.getTimestamp();
                Instant timestamp = Instant.ofEpochMilli(ts);
                Object minVal = minValues.get(ts);
                Object maxVal = protoValueToJava(pt.getValue());

                // Emit min point
                if (minVal != null) {
                    processor.onPointAvailable(key,
                            AggregatedDataPoint.<Object, AggregationType>builder(source, key.aggregationType())
                                    .value(minVal).quality(QualityCode.Good).timestamp(timestamp).build());
                    count++;
                }

                // Emit max point
                if (maxVal != null) {
                    processor.onPointAvailable(key,
                            AggregatedDataPoint.<Object, AggregationType>builder(source, key.aggregationType())
                                    .value(maxVal).quality(QualityCode.Good).timestamp(timestamp).build());
                    count++;
                }
            }
        }
        return count;
    }

    private static String toFactryAggregateFunction(String name) {
        switch (name) {
            case "Average":
            case "SimpleAverage":
                return "mean";
            case "Minimum":
                return "min";
            case "Maximum":
                return "max";
            case "Sum":
                return "sum";
            case "Count":
                return "count";
            case "LastValue":
                return "last";
            case "Range":
                return "spread";
            case "Variance":
                return "variance";
            case "StdDev":
                return "stddev";
            default:
                return "mean";
        }
    }

    /**
     * Convert a TimeWindow to a Go-style duration string (e.g. "2h30m", "45s").
     * Factry uses Go's time.ParseDuration() which does not accept ISO 8601.
     */
    private static String timeWindowToPeriod(TimeWindow tw) {
        long totalSeconds = tw.toSeconds();
        if (totalSeconds <= 0) {
            return "1m";
        }

        StringBuilder sb = new StringBuilder();
        long hours = totalSeconds / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;

        if (hours > 0) sb.append(hours).append("h");
        if (minutes > 0) sb.append(minutes).append("m");
        if (seconds > 0) sb.append(seconds).append("s");

        return sb.length() > 0 ? sb.toString() : "1m";
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

        // Try asset
        Asset a = measurementCache.getAssetByName(tagPath);
        if (a != null) {
            Instant createdTime = a.hasCreatedAt()
                    ? Instant.ofEpochSecond(a.getCreatedAt().getSeconds(), a.getCreatedAt().getNanos())
                    : Instant.EPOCH;
            return Optional.of(new FactryHistoricalNode(a.getUuid(), path, "number", createdTime));
        }

        return Optional.empty();
    }

    private String lookupUUID(String tagPath) {
        // Try measurement
        String uuid = measurementCache.getUUID(tagPath);
        if (uuid != null) return uuid;

        // Try asset
        uuid = measurementCache.getAssetUUID(tagPath);
        if (uuid != null) return uuid;

        // Refresh and retry
        measurementCache.refresh(grpcClient);
        uuid = measurementCache.getUUID(tagPath);
        if (uuid != null) return uuid;
        uuid = measurementCache.getAssetUUID(tagPath);
        return uuid;
    }

    void updateSettings(FactryHistorianSettings newSettings) {
        this.settings = newSettings;
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
