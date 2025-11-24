# Specification

This document is based on 5 days research on this topic. 

# Overview Ignition Historian Module

## Ignition
Ignition is an Industrial Platform for SCADA, IIoT, MES, and More from Inductive Automation. The latest version released is in 2025 august and its version number is 8.3. Ignition is written in Java and Kotlin, the recent 8.3 uses Java version 17. The official package and build manager is gradle, they suggest to use it.

The documentation is generated from the Java code automatically. The Kotlin part doesn't add to the documenation, therefore the documentation usually is not complete. The latest version also lacks of enough number of examples. This causes difficulty to make new modules in the recent version. As a helping hand, Ignition has an official forum, where experts from Inductive automation responds quickly to any question.

## Historian
Historian in industry is a software component which continuously collects, stores, and serves time-series industrial data (like tags from PLCs and devices) so it can be queried, trended, and analyzed later.
Ignition exposes abstract historian api and sdk for making their own internal historian as well as to make it possible to write an external historian module and connect to third party historians.  

The latest version refatored the old way of creating documentation, and although the biggest change is done, this work is not fully ready. Therefore only a limited part can be used from the current API: 
  - 'com.inductiveautomation.historian.gateway.api'
  - 'com.inductiveautomation.historian.common.model'


A little insight:
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



![Abstract classes](abstract_classes.excalidraw.svg)

Ignition module strucutre

TODO: add folder structure and explain it:
  - common
  - client
  - designer
  - gateway
  - certificates  
  - build
  ...etc

Extra folders added:
  - docs < documentation of this project 
  

# Architecture Overview


TODO: gRPC communication (2 sentences: 1.what is it, 2.generate code)

Factry collector: it has features which might be interesting to use for example compression. 

![Architectural Overview](architecture.excalidraw.svg)

Provider doesn't exist yet and Collector might not has all the features implemented. 


# Specification

This chapter elaborates the module development taskts.  

## Historian Function

The list below demostrates the functions in Ignition. 

![Ignition Historian Functions](ignition_historian_function.excalidraw.svg)





Description


  Ignition screenshots with explanations


## Implementation 

# Milestones:
1. Demo  
  ![POC.excalidraw.svg](POC.excalidraw.svg)

2. Historian Collector
3. Historian Provider
   



#Appendix


## Links
System historian fucntions:
https://docs.inductiveautomation.com/docs/8.3/appendix/scripting-functions/system-historian

Custom historian forum
https://forum.inductiveautomation.com/t/ignition-8-3-building-a-custom-tag-historian-module/100725

Gradle

