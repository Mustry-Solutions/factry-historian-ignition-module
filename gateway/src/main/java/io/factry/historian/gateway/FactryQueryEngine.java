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

import io.factry.historian.proto.Measurement;
import io.factry.historian.proto.MeasurementPoints;
import io.factry.historian.proto.QueryPointsReply;
import io.factry.historian.proto.QueryRawPointsRequest;

import com.google.protobuf.util.Timestamps;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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
        logger.info("Browse request for root: " + root);

        try {
            measurementCache.refresh(grpcClient);

            for (Measurement m : measurementCache.getAllMeasurements()) {
                if (publisher.isCanceled()) {
                    return;
                }

                String name = m.getName();
                // Strip the prov:default:/tag: prefix for display if present
                String displayName = name;
                int tagIdx = name.lastIndexOf("tag:");
                if (tagIdx >= 0) {
                    displayName = name.substring(tagIdx + 4);
                }

                Instant createdTime = m.hasCreatedAt()
                        ? Instant.ofEpochSecond(m.getCreatedAt().getSeconds(), m.getCreatedAt().getNanos())
                        : Instant.now();

                publisher.newNode(displayName, "Leaf")
                        .creationTime(createdTime)
                        .hasChildren(false)
                        .add();
            }

            publisher.setTotalResultsAvailable(measurementCache.size());
        } catch (Exception e) {
            logger.error("Error browsing measurements", e);
        }
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
                String uuid = measurementCache.getUUID(tagPath);

                if (uuid == null) {
                    // Try refresh and lookup again
                    measurementCache.refresh(grpcClient);
                    uuid = measurementCache.getUUID(tagPath);
                }

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
        Measurement m = measurementCache.getMeasurementByName(tagPath);
        if (m == null) {
            measurementCache.refresh(grpcClient);
            m = measurementCache.getMeasurementByName(tagPath);
        }
        if (m == null) {
            return Optional.empty();
        }

        Instant createdTime = m.hasCreatedAt()
                ? Instant.ofEpochSecond(m.getCreatedAt().getSeconds(), m.getCreatedAt().getNanos())
                : Instant.EPOCH;

        return Optional.of(new FactryHistoricalNode(m.getUuid(), path, m.getDatatype(), createdTime));
    }

    /**
     * Converts a QualifiedPath from Ignition's internal format to the stored tag path format
     * used as measurement names in Factry.
     *
     * Ignition paths look like: sys:gateway:/histprov:HistorianName:/tag:prov:default:/tag:TagName
     * We need: prov:default:/tag:TagName
     */
    private String toStoredTagPath(QualifiedPath path) {
        String pathStr = path.toString();

        // Strip everything up to and including the histprov segment's /tag: prefix
        int histprovIdx = pathStr.indexOf("histprov:");
        if (histprovIdx >= 0) {
            int tagStart = pathStr.indexOf(":/tag:", histprovIdx);
            if (tagStart >= 0) {
                return pathStr.substring(tagStart + 5); // skip ":/tag:"
            }
        }

        // Fallback: return as-is
        return pathStr;
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
