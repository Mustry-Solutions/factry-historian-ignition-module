# Data Flow & Inherited Classes

## Section 1: Inherited Data Classes

This section documents every Ignition SDK class/interface that the Factry Historian module extends, implements, or consumes. Understanding these is essential for working on either the storage (collector) or query (provider) side.

---

### 1.1 Module Lifecycle

#### `AbstractGatewayModuleHook`
- **What**: Base class for Ignition gateway modules. Entry point for the entire module.
- **Responsibility**: Handles module lifecycle — `setup()`, `startup()`, `shutdown()`. Registers extension points, config panels, and resource bundles.
- **Our impl**: `FactryHistorianGatewayHook` — registers `FactryHistorianExtensionPoint` via `getExtensionPoints()`.

#### `HistorianExtensionPoint<S>`
- **What**: Registers a historian *type* that users can create instances of in the Gateway UI (Config > Services > Historians).
- **Responsibility**: Provides the config form schema, default settings, and a factory method (`createHistorianProvider()`) to instantiate historian instances. Also customizes Gson serialization for settings (e.g. `SecretConfig` adapter).
- **Our impl**: `FactryHistorianExtensionPoint` — type ID `"factry-historian"`, creates `FactryHistoryProvider` instances.

---

### 1.2 Historian Core

#### `AbstractHistorian<S>`
- **What**: The central facade for a historian instance. Implements `Historian<S>`.
- **Responsibility**: Owns the historian's lifecycle (`onStartup()` / `onShutdown()`), holds a name and settings, and exposes `getQueryEngine()` and `getStorageEngine()` for Ignition to call.
- **Our impl**: `FactryHistoryProvider` — creates and wires together `FactryGrpcClient`, `MeasurementCache`, `FactryQueryEngine`, and `FactryStorageEngine`. Also sets up Store & Forward bridges when configured.
- **Key fields**: `historianName`, `started`, `context`, `pathAdapter`.

#### `HistorianSettings` (interface, via `FactryHistorianSettings`)
- **What**: Marker interface for historian configuration POJOs.
- **Responsibility**: Holds all user-configured values (gRPC host/port, collector UUID, token, batch settings, S&F engine name). Serialized to/from JSON by the extension point.
- **Our impl**: `FactryHistorianSettings` — plain POJO with getters/setters. `FactryHistorianConfig` is the record that maps to the Gateway UI form and converts to/from settings.

---

### 1.3 Storage Side (Collector)

#### `StorageEngine` (interface)
- **What**: Ignition's interface for writing historical data to a backend.
- **Key method**: `storeAtomic(List<AtomicPoint<?>>)` → `CompletionStage<StorageResult<AtomicPoint<?>>>`
- **Responsibility**: Receives batches of timestamped data points from tag subscriptions and persists them.

#### `AbstractStorageEngine`
- **What**: Base implementation of `StorageEngine`. Handles threading, batching strategy, and delegates to abstract methods.
- **Responsibility**: Calls our `doStoreAtomic()` and `applySourceChanges()`. Manages `PointStorageStrategy` (immediate vs. batched).
- **Our impl**: `FactryStorageEngine` — converts `AtomicPoint` values to protobuf `Point` messages and sends them via `FactryGrpcClient.createPoints()`.
- **Abstract methods we override**:
  - `doStoreAtomic(List<AtomicPoint<?>>)` → `StorageResult` — the actual write logic
  - `applySourceChanges(List<SourceChangePoint>)` → `StorageResult` — tag rename/retire (no-op for us)
  - `isEngineUnavailable()` → `boolean` — always false (let gRPC errors trigger S&F retry)

#### `AtomicPoint<V>`
- **What**: A single timestamped data point. Extends `DataPoint<V>`.
- **Responsibility**: Carries a `value()`, `quality()` (QualityCode), `timestamp()` (Instant), and `source()` (QualifiedPath). This is the fundamental unit of historical data in both storage and query.
- **Used in**: `doStoreAtomic()` (storage receives them), `doQueryRaw()` (query produces them via `DataPointFactory`).
- **Key methods**: `value()`, `quality()`, `timestamp()`, `source()`, `type()`.

#### `StorageResult<T>`
- **What**: Result wrapper returned from storage operations.
- **Responsibility**: Tells the framework whether the write succeeded, failed, or partially succeeded. Controls Store & Forward retry behavior.
- **Key factory methods**:
  - `StorageResult.success(points)` — all good
  - `StorageResult.exception(e, points)` — triggers S&F retry via `DataStorageException`
  - `StorageResult.failure(points)` — permanent failure, data quarantined

#### `SourceChangePoint`
- **What**: Represents a tag path rename or retirement event.
- **Responsibility**: Tells the historian that a tag's source path changed. Our implementation ignores these (returns success).

#### `QualityCode`
- **What**: Ignition's quality/status code for data values.
- **Responsibility**: Indicates whether a value is `Good`, `Uncertain`, or `Bad`. Mapped to/from Factry's string status ("Good"/"Uncertain"/"Bad").
- **Key constants**: `QualityCode.Good`, `QualityCode.Uncertain`, `QualityCode.Bad`, `QualityCode.Bad_NotFound`.

#### `ImmediateStorageStrategy`
- **What**: A `PointStorageStrategy` that writes points immediately without local batching.
- **Responsibility**: Passes incoming points straight through to `doStoreAtomic()` without accumulating them. We use this because batching is handled by the gRPC layer / S&F.

---

### 1.4 Store & Forward Bridges

#### `TagHistoryStorageEngineBridge`
- **What**: A `StorageEngine` wrapper that routes writes into Ignition's Store & Forward system instead of writing directly.
- **Responsibility**: Replaces the direct `FactryStorageEngine` as the historian's storage engine when S&F is enabled. Incoming data goes to S&F first, then S&F forwards it to the sink.
- **Used in**: `FactryHistoryProvider.getStorageEngine()` returns this bridge (if S&F configured) instead of the raw engine.

#### `TagHistoryDataSinkBridge`
- **What**: An S&F sink that receives forwarded data from S&F and writes it to the actual storage engine.
- **Responsibility**: Registered with `StoreAndForwardManager`. Calls our `FactryStorageEngine.storeAtomic()` when S&F forwards data. Throws `DataStorageException` on `StorageResult.exception()` to trigger S&F retry/quarantine.
- **Key method**: `storeData(List<TagHistoryData>)` — converts S&F data back to historian points and stores them.

---

### 1.5 Query Side (Provider)

#### `QueryEngine` (interface)
- **What**: Ignition's interface for reading historical data from a backend.
- **Key methods**:
  - `browse(path, filter, publisher)` — list available tags
  - `query(RawQueryOptions, RawPointProcessor)` — fetch raw time-series data
- **Responsibility**: Called by Power Chart, Tag History bindings, and other Ignition components to retrieve historical data.

#### `AbstractQueryEngine`
- **What**: Base implementation of `QueryEngine`. Handles path normalization, metrics, and delegates to abstract methods.
- **Our impl**: `FactryQueryEngine`.
- **Abstract methods we override**:
  - `doBrowse(path, filter, publisher)` — browse available measurements
  - `doQueryRaw(options, processor)` → `Optional<Integer>` — fetch raw points
  - `lookupNode(path)` → `Optional<HistoricalNode>` — find a single tag's metadata
  - `queryForHistoricalNodes(paths, timeRange)` → `Map<path, HistoricalNode>` — batch lookup
  - `isEngineUnavailable()` → `boolean` — always false

#### `HistoricalNode`
- **What**: Metadata about a historical tag. Returned by `lookupNode()` and `queryForHistoricalNodes()`.
- **Responsibility**: Tells Ignition the tag's UUID, source path, data type, and creation/retirement times. Used internally by `AbstractQueryEngine.mapKeysToNodes()` before executing queries.
- **Our impl**: `FactryHistoricalNode` — maps a Factry `Measurement` (uuid, name, datatype, createdAt) to the Ignition interface.
- **Key methods**: `nodeId()`, `source()`, `dataType()`, `createdTime()`, `retiredTime()`.

#### `DataType` (enum)
- **What**: `com.inductiveautomation.ignition.common.sqltags.model.types.DataType` — Ignition's tag data type enum.
- **Responsibility**: Used in `HistoricalNode.dataType()` to tell Ignition what kind of values to expect.
- **Mapping**: Factry `"number"` → `DataType.Float8`, `"boolean"` → `DataType.Boolean`, `"string"` → `DataType.String`.

#### `RawQueryOptions`
- **What**: Parameters for a raw data query. Extends `DataPointQueryOptions<RawQueryKey>`.
- **Responsibility**: Carries the list of query keys (tag paths), time range, and ordering. Passed to `doQueryRaw()`.
- **Key methods**: `getQueryKeys()` → `List<RawQueryKey>`, `getTimeRange()` → `Optional<TimeRange>`.

#### `RawQueryKey`
- **What**: A Java record identifying a single tag in a query. Wraps a `QualifiedPath`.
- **Responsibility**: Used as the key when feeding points to the processor and when reporting failures.
- **Key method**: `source()` → `QualifiedPath` (the tag's qualified path).

#### `QualifiedPath`
- **What**: Ignition's hierarchical path representation (e.g. `sys:gateway:/histprov:Name:/tag:prov:default:/tag:TagName`).
- **Responsibility**: Universal identifier for tags across the system. Used in browse results, query keys, and storage points. Our `toStoredTagPath()` strips the Ignition prefix to get the Factry measurement name.

#### `BrowsePublisher`
- **What**: Callback interface for publishing browse results.
- **Responsibility**: Called from `doBrowse()` to add tags/folders to the browse tree (e.g. Power Chart tag browser).
- **Key methods**:
  - `newNode(name, nodeType)` → `NodeBuilder` — create a node ("Leaf" or "Folder")
  - `isCanceled()` — check if user cancelled the browse
  - `setTotalResultsAvailable(count)` — report total available
  - `recurse()` — browse into a subfolder

#### `BrowsePublisher.NodeBuilder`
- **What**: Builder for a single browse result node.
- **Responsibility**: Set node properties then call `add()` to publish it.
- **Key methods**: `creationTime(Instant)`, `retiredTime(Instant)`, `hasChildren(boolean)`, `add()`.

#### `RawPointProcessor`
- **What**: Callback interface for receiving query results. Extends `QueriedPointProcessor<AtomicPoint<?>, RawQueryKey>`.
- **Responsibility**: Ignition passes this to `doQueryRaw()`. We feed it data points and it assembles the DataSet for Power Chart.
- **Key methods**:
  - `onInitialize(ProcessingContext)` → `boolean` — must be called first, returns false to abort
  - `onPointAvailable(RawQueryKey, AtomicPoint)` → `boolean` — feed one point, returns false to stop
  - `onKeyFailure(RawQueryKey, QualityCode)` — report a tag that couldn't be queried
  - `onError(Exception)` — report a fatal error
  - `onComplete()` — signal end of data

#### `ProcessingContext<K, T>`
- **What**: Configuration context for point processors.
- **Responsibility**: Maps each query key to its `PropertySet` configuration. Must be passed to `processor.onInitialize()` before feeding points.
- **Our usage**: `DefaultProcessingContext.<RawQueryKey, DataPointType>builder().build()` — empty context (no per-key properties needed).

#### `DataPointFactory`
- **What**: Factory class for creating `AtomicPoint` instances.
- **Responsibility**: Creates properly-typed atomic points from raw values. Used in `doQueryRaw()` to convert Factry protobuf `Point` messages into Ignition `AtomicPoint` objects.
- **Key method**: `createAtomicPoint(value, quality, timestamp, path)` — creates a point with all fields.

#### `TimeRange`
- **What**: A time interval with `startTime()` and `endTime()` (both `Instant`).
- **Responsibility**: Defines the query window. Extracted from `RawQueryOptions.getTimeRange()` and converted to protobuf `Timestamp` for the gRPC call.

#### `BrowseFilter`
- **What**: Filter criteria for browse operations.
- **Responsibility**: Passed to `doBrowse()` by Ignition. Can contain name patterns and type filters. Currently not used in our implementation (we return all active measurements).

---

### 1.6 Supporting Classes

#### `MeasurementCache` (ours, not inherited)
- **What**: In-memory cache mapping tag paths ↔ Factry measurement UUIDs.
- **Responsibility**: Central lookup for both storage (tag path → UUID for writing) and query (UUID → measurement metadata for browsing/reading). Calls `GetMeasurements` gRPC to populate.

#### `FactryGrpcClient` (ours, not inherited)
- **What**: gRPC client wrapper for the Factry Historian proxy.
- **Responsibility**: Manages the gRPC channel, attaches auth headers, and provides typed methods: `createPoints()`, `getMeasurements()`, `createMeasurements()`, `queryRawPoints()`.

---

## Section 2: Data Flow Diagrams

### 2.1 Module Startup Flow

```
Ignition Gateway Boot
        │
        ▼
FactryHistorianGatewayHook.setup()
        │  registers resource bundle
        ▼
FactryHistorianGatewayHook.getExtensionPoints()
        │  returns [FactryHistorianExtensionPoint]
        ▼
Ignition discovers historian type "factry-historian"
        │
        ▼
For each saved historian profile:
        │
        ▼
FactryHistorianExtensionPoint.createHistorianProvider()
        │  reads FactryHistorianConfig → FactryHistorianSettings
        ▼
new FactryHistoryProvider(context, name, settings)
        │
        ├── new FactryGrpcClient(host, port, collectorUUID, token)
        ├── new MeasurementCache()
        ├── new FactryQueryEngine(context, name, settings, grpcClient, cache)
        └── new FactryStorageEngine(context, name, settings, grpcClient, cache)
        │
        ▼
FactryHistoryProvider.onStartup()
        │
        ├── measurementCache.refresh(grpcClient)
        │       │
        │       └── gRPC: GetMeasurements() → populate cache
        │
        └── [if S&F configured]
                ├── TagHistoryDataSinkBridge.getOrCreate()
                ├── registerSink() + force initialize()
                └── TagHistoryStorageEngineBridge.getOrCreate()
```

### 2.2 Storage Flow (Tag Value → Factry)

```
Ignition Tag Subscription
  (tag value changes)
        │
        ▼
StorageEngine.storeAtomic(List<AtomicPoint<?>>)
        │
        ▼
┌─── S&F Enabled? ───┐
│                     │
│ YES                 │ NO
▼                     ▼
TagHistoryStorage     FactryStorageEngine
EngineBridge          .doStoreAtomic()
    │                     │
    ▼                     │
S&F Engine                │
(persist to disk)         │
    │                     │
    ▼                     │
TagHistoryData            │
SinkBridge                │
    │                     │
    ▼                     │
FactryStorageEngine  ◄────┘
.doStoreAtomic(points)
        │
        │  For each AtomicPoint:
        │  ┌──────────────────────────────────┐
        │  │ tagPath = point.source().toString │
        │  │ uuid = cache.getOrCreateUUID()    │
        │  │ value → protobuf Value            │
        │  │ quality → status string           │
        │  │ timestamp → protobuf Timestamp    │
        │  └──────────────────────────────────┘
        ▼
FactryGrpcClient.createPoints(Points)
        │
        ▼
gRPC: CreatePoints ──────► Factry Historian
        │
        ▼
StorageResult.success() ── or ── StorageResult.exception()
                                        │
                                        ▼
                                  [S&F retries later]
```

### 2.3 Query Flow (Power Chart → Factry)

```
Power Chart / Tag History Binding
  "Show me data for tag X from T1 to T2"
        │
        ▼
AbstractQueryEngine.query(RawQueryOptions, RawPointProcessor)
        │  normalizes paths, maps keys to nodes
        ▼
FactryQueryEngine.doQueryRaw(options, processor)
        │
        ├── 1. Build ProcessingContext
        │       DefaultProcessingContext.<RawQueryKey, DataPointType>builder().build()
        │
        ├── 2. processor.onInitialize(context)
        │       (if false → abort)
        │
        ├── 3. Map query keys to measurement UUIDs
        │       For each RawQueryKey:
        │       ┌─────────────────────────────────────────────┐
        │       │ path = key.source()                          │
        │       │ tagPath = toStoredTagPath(path)              │
        │       │   strips "sys:gateway:/histprov:Name:/tag:"  │
        │       │   → "prov:default:/tag:TagName"              │
        │       │ uuid = measurementCache.getUUID(tagPath)     │
        │       └─────────────────────────────────────────────┘
        │
        ├── 4. gRPC: QueryRawPoints(uuids, startTime, endTime)
        │       │
        │       ▼
        │   FactryGrpcClient.queryRawPoints()
        │       │
        │       ▼
        │   Factry Historian ──► QueryPointsReply
        │       │                  { measurementPoints: [
        │       │                      { uuid, points: [
        │       │                          { timestamp, value, status }
        │       │                      ]}
        │       │                  ]}
        │       ▼
        ├── 5. Convert each protobuf Point to AtomicPoint
        │       ┌────────────────────────────────────────┐
        │       │ timestamp → Instant                     │
        │       │ status → QualityCode                    │
        │       │ value → Java Object (Double/Boolean/String) │
        │       │ DataPointFactory.createAtomicPoint(     │
        │       │     value, quality, timestamp, path)    │
        │       └────────────────────────────────────────┘
        │
        ├── 6. processor.onPointAvailable(key, atomicPoint)
        │       (for each point, returns false to stop early)
        │
        └── 7. processor.onComplete()
                │
                ▼
        RawPointProcessor assembles DataSet
                │
                ▼
        Power Chart renders the data
```

### 2.4 Browse Flow (Tag Browser → Factry)

```
Power Chart Tag Browser
  "Show available tags"
        │
        ▼
AbstractQueryEngine.browse(rootPath, filter, publisher)
        │
        ▼
FactryQueryEngine.doBrowse(root, filter, publisher)
        │
        ├── measurementCache.refresh(grpcClient)
        │       │
        │       └── gRPC: GetMeasurements() → update cache
        │
        └── For each active Measurement in cache:
                │
                ├── Extract display name from measurement name
                │   "prov:default:/tag:Temperature" → "Temperature"
                │
                └── publisher.newNode("Temperature", "Leaf")
                        .creationTime(m.createdAt)
                        .hasChildren(false)
                        .add()
                │
                ▼
        BrowsePublisher collects results
                │
                ▼
        Tag browser shows list of measurements
```

### 2.5 Node Lookup Flow

```
AbstractQueryEngine (internal, before query execution)
  "What do I know about this tag path?"
        │
        ▼
FactryQueryEngine.lookupNode(QualifiedPath)
        │
        ├── toStoredTagPath(path)
        │   "sys:..histprov:Name:/tag:prov:default:/tag:X" → "prov:default:/tag:X"
        │
        ├── measurementCache.getMeasurementByName(tagPath)
        │   (refresh from gRPC if not found)
        │
        └── return FactryHistoricalNode(uuid, path, datatype, createdAt)
                │
                ▼
        AbstractQueryEngine uses nodeId, dataType
        for query routing and processor configuration
```

### 2.6 Measurement Cache Interactions

```
                    ┌──────────────────┐
                    │ MeasurementCache │
                    │                  │
                    │ tagPath → UUID   │
                    │ UUID → Measurement│
                    └──────┬───────────┘
                           │
          ┌────────────────┼────────────────┐
          │                │                │
    ┌─────▼──────┐   ┌────▼─────┐   ┌─────▼──────┐
    │  Storage   │   │  Query   │   │  Browse    │
    │  Engine    │   │  Engine  │   │            │
    │            │   │          │   │            │
    │getOrCreate │   │ getUUID  │   │ getAll     │
    │UUID()      │   │()        │   │Measurements│
    │            │   │getMeas   │   │()          │
    │(creates if │   │ByName()  │   │            │
    │ not found) │   │ByUUID()  │   │            │
    └────────────┘   └──────────┘   └────────────┘
          │                │                │
          ▼                ▼                ▼
    tag path→UUID    tag path→UUID    all active
    (+ auto-create)  (read only)      measurements
```
