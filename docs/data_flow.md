# Data Flow Diagrams

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
        └── return FactryRecord(uuid, path, datatype, createdAt)
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
                    │ tagPath → metadata│
                    │   (pending)      │
                    └──────┬───────────┘
                           │
     ┌─────────────────────┼────────────────┐
     │                     │                │
┌────▼───────┐  ┌─────────▼────┐   ┌───────▼────┐
│  Storage   │  │  Storage     │   │  Query     │
│  Engine    │  │  Engine      │   │  Engine    │
│ (atomic)   │  │ (metadata)   │   │            │
│            │  │              │   │ getUUID()  │
│getOrCreate │  │storeMetadata │   │ getMeas    │
│UUID()      │  │()            │   │ ByName()   │
│            │  │              │   │ ByUUID()   │
│(creates +  │  │(caches props │   │ getAll     │
│ applies    │  │ for future   │   │ Measurements│
│ metadata)  │  │ creates)     │   │()          │
└────────────┘  └──────────────┘   └────────────┘
      │                │                  │
      ▼                ▼                  ▼
tag path→UUID    cache metadata     tag path→UUID
(+ auto-create   until measurement  (read only) +
 with metadata)  is created         all active
```
