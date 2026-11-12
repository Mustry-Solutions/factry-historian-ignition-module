package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.query.HistoricalNode;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public class FactryRecord implements HistoricalNode {
    private final UUID nodeId;
    private final String measurementUUID;
    private final int arrayIndex;
    private final QualifiedPath source;
    private final DataType dataType;
    private final Instant createdTime;

    public FactryRecord(String measurementUUID, QualifiedPath source, String factryDataType, Instant createdTime) {
        this(measurementUUID, -1, source, factryDataType, createdTime);
    }

    /**
     * A node for one element of an array measurement. Ignition historizes {@code Tag[i]} as its
     * own path, while Factry stores the whole array in the measurement {@code Tag}.
     *
     * @param arrayIndex the element index, or -1 when the node is the whole measurement
     * @param factryDataType the element's data type (e.g. "number" for a "[]number" measurement)
     */
    public FactryRecord(String measurementUUID, int arrayIndex, QualifiedPath source,
                        String factryDataType, Instant createdTime) {
        // Elements share a measurement, so the index is part of the node id to keep it unique.
        String id = arrayIndex >= 0 ? measurementUUID + "[" + arrayIndex + "]" : measurementUUID;
        this.nodeId = UUID.nameUUIDFromBytes(id.getBytes());
        this.measurementUUID = measurementUUID;
        this.arrayIndex = arrayIndex;
        this.source = source;
        this.dataType = toIgnitionDataType(factryDataType);
        this.createdTime = createdTime != null ? createdTime : Instant.EPOCH;
    }

    /** The Factry UUID to query: the measurement (or asset) this node reads from. */
    public String measurementUUID() {
        return measurementUUID;
    }

    /** The array element this node reads, or -1 when it reads the whole value. */
    public int arrayIndex() {
        return arrayIndex;
    }

    @Override
    public UUID nodeId() {
        return nodeId;
    }

    @Override
    public QualifiedPath source() {
        return source;
    }

    @Override
    public Optional<DataType> dataType() {
        return Optional.ofNullable(dataType);
    }

    @Override
    public Instant createdTime() {
        return createdTime;
    }

    @Override
    public Optional<Instant> retiredTime() {
        return Optional.empty();
    }

    static DataType toIgnitionDataType(String factryDataType) {
        if (factryDataType == null) {
            return DataType.Float8;
        }
        switch (factryDataType) {
            case "boolean":
                return DataType.Boolean;
            case "number":
                return DataType.Float8;
            case "string":
                return DataType.String;
            default:
                return DataType.Float8;
        }
    }
}
