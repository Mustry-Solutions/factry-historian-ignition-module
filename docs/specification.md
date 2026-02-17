# Factry historian module specification



## Feature set

Factry historian module should implement:
 - Two separate components: A Collector (Storage Provider) and a Provider (History Provider)
 - Local service architecture: Consider a local buffer/forward service on the Ignition Gateway for resilience
 - communication: gPRC- Factry hast to expose some more endpoints over gRPC 
 - gPRC schemas are defined with protobuf files, we should generate Java objects
 - Integration with Tag History Module: Leverage Ignition's built-in history configuration (deadbands, sample modes, etc.)
 - DataSet/grouping concept: Logical organization of tags
 - Standard provider interface: So Ignition components can query your historian like any other
  - store/forward is handled by Ignition
  - Split write and read config: allow calculations to be read from Ignition
  - Tag creation
	•	When tag is selected for historizing → create measurement with API
	•	Nice-to-have: engineering units
	•	Nice-to-have: create asset path from Ignition hierarchy
	•	Tag handling / Asset tree
  •	4 functions defined, should all be supported: Browse, Read, …



Still question for answer:
  - Open-source? (Probably yes)
  - Expand Factry query endpoint to support aggregates

Task needed to done by Factry:
   - gPRC communication
   - Expand Factry query endpoint to support aggregates








