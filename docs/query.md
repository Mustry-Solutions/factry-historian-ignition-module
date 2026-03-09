# Query gRPC API — Proposal for Factry Team

## Terminology

The Ignition module has two sides that map to Factry concepts:

| Ignition (module) | Factry (server) | Description |
|---|---|---|
| **Store** (StorageEngine) | **Collector** | Writing tag data into Factry |
| **Query** (QueryEngine) | **Provider** | Reading data back from Factry |

This document focuses on the **Query ↔ Provider** side.

## Context

The Ignition module connects to Factry Historian via gRPC. The current protobuf defines RPCs for the **store** side (writing data to the Factry collector), but the **query** side (reading data from the Factry provider) is not yet covered.

Ignition's query engine needs to support two operations:

1. **Browse** — List available data sources (measurements, assets, calculations) so users can select them in the Power Chart tag picker
2. **Query** — Retrieve time-series data points for selected measurements within a time range

The existing `GetMeasurements` RPC returns measurement metadata, which partially covers browsing. However, there is no RPC for querying actual time-series data, and no way to browse assets or calculations.

This document proposes the gRPC additions needed. 

## What Ignition Needs

When a user opens a Power Chart in Ignition and wants to add historical data:

1. **Tag browsing**: Ignition calls `doBrowse()` on the query engine — we need to list all available measurements (and ideally assets/calculations) with their names and data types
2. **Raw data query**: Ignition calls `doQueryRaw()` (via `RawPointProcessor`) — fetch unprocessed time-series points for one or more measurements within a time range, ordered by timestamp
3. **Aggregated data query**: Ignition calls `doQueryAggregated()` (via `AggregatedPointProcessor`) — fetch data with server-side aggregation functions (average, min, max, first, last, count, etc.) applied over time buckets
4. **Metadata query**: Ignition calls `queryMetadata()` — retrieve metadata about historical tags (data type, engineering units, limits)

## Current Proto — What We Already Have

```protobuf
// Browsing measurements (metadata only, no point values)
rpc GetMeasurements (MeasurementRequest) returns (Measurements);

message MeasurementRequest {
  google.protobuf.Timestamp since = 1;
  string collectorUUID = 2;
}

message Measurement {
  string uuid = 1;
  string name = 2;
  string status = 3;
  google.protobuf.Timestamp createdAt = 4;
  google.protobuf.Timestamp updatedAt = 5;
  google.protobuf.Struct settings = 6;
  string datatype = 7;
  EngineeringSpecs engineeringSpecs = 8;
}
```

This is sufficient for browsing measurements. We can already use this to populate the Power Chart tag picker with measurement names and types.

## Proposed Additions

### 1. Query Raw Time-Series Data

The Ignition query engine needs to retrieve raw (unprocessed) historical point values from the Factry provider. This is used by `doQueryRaw()` / `RawPointProcessor`.

```protobuf
// New RPC — Ignition query engine calls this on the Factry provider
rpc QueryRawPoints (QueryRawPointsRequest) returns (QueryPointsReply);

message QueryRawPointsRequest {
  repeated string measurementUUIDs = 1;       // Which measurements to query
  google.protobuf.Timestamp startTime = 2;    // Start of time range (inclusive)
  google.protobuf.Timestamp endTime = 3;      // End of time range (inclusive)
  optional int32 maxPoints = 4;               // Limit number of returned points (per measurement)
  optional bool includeBounds = 5;            // Include boundary points at start/end of range
}

message QueryPointsReply {
  repeated MeasurementPoints measurementPoints = 1;
}

message MeasurementPoints {
  string measurementUUID = 1;
  repeated Point points = 2;                  // Reuses existing Point message
}
```

### 2. Query Aggregated Time-Series Data

Ignition also supports aggregated queries via `doQueryAggregated()` / `AggregatedPointProcessor`. This is used when Power Charts or scripts request downsampled data over a time range (e.g., "show me the average temperature per hour for the last week").

```protobuf
rpc QueryAggregatedPoints (QueryAggregatedPointsRequest) returns (QueryPointsReply);

message QueryAggregatedPointsRequest {
  repeated string measurementUUIDs = 1;       // Which measurements to query
  google.protobuf.Timestamp startTime = 2;    // Start of time range (inclusive)
  google.protobuf.Timestamp endTime = 3;      // End of time range (inclusive)
  int64 intervalMs = 4;                       // Aggregation bucket size in milliseconds
  repeated string aggregates = 5;             // e.g., "avg", "min", "max", "first", "last", "count", "sum"
}
```

**Notes:**
- Both RPCs reuse the existing `Point` message (measurementUUID, timestamp, value, status)
- `maxPoints` helps prevent excessive data transfer for large time ranges
- Grouping the response by measurement makes it easy to map back to Ignition's per-tag query model
- If Factry does not support server-side aggregation, only `QueryRawPoints` is required — Ignition can aggregate client-side

**Minimal version** (if Factry prefers to start simple — raw queries only):

```protobuf
rpc QueryRawPoints (QueryRawPointsRequest) returns (Points);  // Reuses existing Points message

message QueryRawPointsRequest {
  repeated string measurementUUIDs = 1;
  google.protobuf.Timestamp startTime = 2;
  google.protobuf.Timestamp endTime = 3;
  optional int32 maxPoints = 4;
}
```

### 2. Browse Assets (Optional)

If Factry wants assets to appear as a browsable hierarchy in Ignition's tag picker:

```protobuf
rpc GetAssets (GetAssetsRequest) returns (Assets);

message GetAssetsRequest {
  optional string parentUUID = 1;             // null = root level
}

message Asset {
  string uuid = 1;
  string name = 2;
  optional string parentUUID = 3;
  repeated string measurementUUIDs = 4;       // Measurements linked to this asset
}

message Assets {
  repeated Asset assets = 1;
}
```

This would allow us to show a tree structure in the Ignition tag browser: `Asset > Sub-Asset > Measurement`.

### 3. Browse Calculations (Optional)

If calculations should also be queryable from Ignition:

```protobuf
rpc GetCalculations (GetCalculationsRequest) returns (Calculations);

message GetCalculationsRequest {
  optional string collectorUUID = 1;
}

message Calculation {
  string uuid = 1;
  string name = 2;
  string datatype = 3;
  string status = 4;
}

message Calculations {
  repeated Calculation calculations = 1;
}
```

Calculations could be queried using the same `QueryPoints` RPC if their UUIDs are compatible with measurement UUIDs, or they may need a separate `QueryCalculationPoints` RPC.

## Usage Flow

```
User opens Power Chart → "Add Tag"
    │
    ├─ Ignition query engine calls doBrowse()
    │   └─ Module calls GetMeasurements on Factry provider (+ GetAssets, GetCalculations)
    │       └─ Shows list: [Temperature, Pressure, Flow Rate, ...]
    │
    └─ User selects "Temperature", sets time range
        │
        ├─ Raw mode → Ignition calls doQueryRaw()
        │   └─ Module calls QueryRawPoints on Factry provider
        │       └─ Returns all data points → rendered on Power Chart
        │
        └─ Aggregated mode → Ignition calls doQueryAggregated()
            └─ Module calls QueryAggregatedPoints on Factry provider
                └─ Returns bucketed data (e.g., avg per hour) → rendered on Power Chart
```

## Summary of Proposed RPCs

| RPC | Direction | Purpose | Priority |
|-----|-----------|---------|----------|
| `GetMeasurements` | Query → Provider | Browse measurement metadata | Already exists |
| `QueryRawPoints` | Query → Provider | Retrieve raw time-series data | Required |
| `QueryAggregatedPoints` | Query → Provider | Retrieve aggregated time-series data | Nice to have (Ignition can aggregate client-side) |
| `GetAssets` | Query → Provider | Browse asset hierarchy | Optional |
| `GetCalculations` | Query → Provider | Browse calculations | Optional |

## Questions for Factry

1. **QueryPoints**: Is the proposed request/response structure compatible with how Factry stores and indexes data? Any preferred changes?
2. **Aggregation**: Should aggregation be done server-side (in Factry) or is returning raw data sufficient? Ignition can aggregate client-side.
3. **Assets & Calculations**: Should these be browsable from Ignition? If so, are the proposed messages reasonable?
4. **Streaming**: For large queries, would a streaming response (`stream QueryPointsReply`) be preferred over a single response?
5. **Authentication**: The current `collectoruuid` + `authorization` headers work for store RPCs. Should query RPCs use the same authentication or a different mechanism?
6. **Rate limiting / pagination**: Any concerns about large time-range queries? Should we add cursor-based pagination?
