# Design Overview


## Ignition Platform

Ignition is an industrial automation platform (SCADA, IIoT, MES) from Inductive Automation, written in Java/Kotlin targeting Java 17 (v8.3). Its module SDK allows extending functionality with custom modules built with Gradle.

Ignition does not store historical data internally — it relies on external databases or third-party historians. This is why many historian vendors provide Ignition modules, and why this Factry Historian module exists.

We are building a module, which communicates directly with Factry Historian:

![module.excalidraw.svg](module.excalidraw.svg)

The module communicates with Factry services using gRPC, a high-performance RPC framework designed for low-latency, high-throughput communication.

Protocol Buffers (protobuf)  defines the message formats and service interfaces shared between Factry and the Ignition module. 

## Historian SDK

Ignition 8.3 introduced a refactored Historian API in two packages: `com.inductiveautomation.historian.gateway.api` and `com.inductiveautomation.historian.common.model`. Implementation requires extending abstract base classes:

```
com.inductiveautomation.historian.gateway.api
├── Historian<S>                    - Main historian interface
├── AbstractHistorian<S>            - Base implementation class
├── HistorianManager                - System historian manager
├── config/
│   └── HistorianSettings          - Configuration marker interface
├── query/
│   ├── QueryEngine                - Data retrieval interface
│   ├── AbstractQueryEngine        - Base query implementation
│   ├── browsing/
│   │   └── BrowsePublisher        - Tag browsing API
│   └── processor/
│       ├── RawPointProcessor      - Raw data processing
│       ├── AggregatedPointProcessor - Aggregated data processing
│       └── ComplexPointProcessor  - Complex data processing
├── storage/
│   ├── StorageEngine              - Data storage interface
│   └── AbstractStorageEngine      - Base storage implementation
└── paths/
    └── QualifiedPathAdapter       - Path normalization
```



