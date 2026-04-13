# Weekly Meetings

# 09/03/2026 — Collect Tag Changes (Collector)

First version of sending tag changes to the Factry collector.

Prior work:
  - Scaffolded the Ignition module
  - Wrote the design document
  - Installed the module, created a historian, and assigned it to a tag

Recently:
  - Implemented gRPC collector integration for the module
  - Tidied up documentation
  - Some clarifications

See [try-out.md](try-out.md) for setup instructions.

> This falls under "Milestone 1: Proof of Concept" (proposed milestones).
> (Milestone 2: Historian Collector Full implementation)

Known issues:
  - Store and forward is not yet implemented (in progress)
  - Editing settings is not possible in Ignition; the historian must be recreated

> Terminology:
>  - Store (Ignition) → Collector (Factry) — writing tag data                 
>  - Query (Ignition) → Provider (Factry) — reading data back

Questions:
  - tag name: 'prov:default:/tag:Alma' 
  - The provider query API is not yet available, but browsing measurements can already be done. Only measurements, or also assets/calculations?
  - Should we set up a docker-compose with everything (Ignition + Factry Historian + databases)?
  - How should the module and Ignition be installed (Docker)?
  - Documentation structure:
      - Design document and related assets
      - Module signing guide
      - Try-out guide


# 16/03/2026

Done:
 [+] bug: creating two measurements for one tag, because it didn't wait for the first
 [+] Editing settings
 [+] Store and forward
 
> (Milestone 2: Historian Collector Full implementation < is this enough?)

 [+] preliminary protobuf + emulator
 [+] Browsing measurements from the emulator
 
known issue:
  - missing point in store and forward
  - new point is not always flushed 
  - if the requests fails for measuremnts -> shows old instead of empty

> remark: one 'store and forward' object can serve several historian (or anything else), the pathtag includes the provider

Questions:
  - measurements | calculation | assets in separate folder?
> the protobuf with the new query data is welcome as soon as possible
  - wait for the protobuf or go further? 

# 23/03/2026

changes/remarks:

>   *pending* : points queue, the ignition S&F tries to send them periodically
> 
>   *quarantined* :  malformed points
>       how to decide if network error or malformed point: we need a proper error message in case of the second

 
  + missing point in store and forward
     
     . isEngineUnavailable() < always returned false, now it is correct (checks periodically)
     
     . adds a 3-second deadline to createPoints() so failures are detected quickly
          flag flips to disconnected and isEngineUnavailable() returns true
     
     . possible improvement: return error is it is malformed otherwise  set isEngineUnavailable()

  + if the requests fails for measurements -> shows old instead of empty

>  + *new point is not always flushed*:
>   This is standard Ignition historian behavior, not a bug in our code. The current value gets flushed when the next value change occurs.
>   That's a classic off-by-one timing issue — Ignition's historian likely sends the previous value when a new change arrives (that's how SourceChangePoint / deadband works: it confirms the old value held until the new one arrived). 
>


  + handleChangeSettings — all classes is in the code  support dynamic
  settings changes without module restart                                     
  + Logging improvements — FactryHistoryProvider logging adjustments         
  + testConnection() + getStatus polling — FactryGrpcClient got a            
  testConnection() method; FactryHistoryProvider polls 30 seconds to  
  track connection status                                                     
  + Aggregated query support — FactryQueryEngine got 
      doQueryAggregated() 
      getNativeAggregates() < list, but not sure it is correct
  + Metrics — New HistorianMetrics class (118 lines) for tracking store/query
   performance


  + adopted new proto from factrylabs/historian-proto 
    - replaced QueryRawPoints with QueryTimeseries (no aggregation = raw query)
    - removed Calculations as a separate concept, no separate folder in Power Chart tag browser
    - updated Asset message to new richer format (parentUUID, assetPath, attributes, metadata)
    - replaced GetAssets(AssetRequest) with GetAssets(GetAssetsRequest) with filtering support
    - updated Aggregation fields: function→name, fill→fillType, added arguments
    - updated fake gRPC server with all new RPCs
 

Questions about the new proto:
  - Series.fields (repeated string) < Column names for multi-value series
  - QueryTimeseriesRequest.join (bool) - skip, not important
  - QueryTimeseriesRequest.onlyChanges (bool)  - sends only changes, so 1,2,2,3 -> 1,2,3
  

Other questions
  - what should happen if the tag is removed? should we remove the measurements?
      FactryStorageEngine.applySourceChanges explicitly does nothing, just logs a debug message                          
  - what does the calculation collector mean
  - testing strategies?
      - automated scripts on gateway to change the tags
      - run the jython code the get the data
      - insert a lot of data, check the performance
      - compare the performance to grafana
      - send integer first, try to send float
      - change to discrete, is the last point immediatly send
    -   

# 30/03/2026

changes:
  - TLS: Added "Skip TLS Verification" setting, proper cert validation by default
    (it skipped automatically before)
    Question:
     If publicly-signed: nothing to do
     If self-signed/internal CA: we'd need a setting for the user to provide a CA  
  certificate path.
  
  - Config validation: FactryHistorianSettings.validate() — checks host, port,     
  collectorUUID, batchSize, batchIntervalMs    
     (use token)
 
  - Malformed point handling: StatusRuntimeException catch distinguishes             
     UNAVAILABLE/DEADLINE_EXCEEDED (retry) from INVALID_ARGUMENT (quarantine)
  
  - Logging cleanup: ~50 logger.info() calls downgraded to debug(), separator banners
   removed                 

  - little refactor: 
      gRPC client refactor: extracted buildChannel() and tlsLabel(), eliminated code   
  duplication   

  - unit tests:                                                                   
      - FactryHistorianSettingsTest — 18 tests for validation                            
      - HistorianMetricsTest — 10 tests for metrics tracking                           
      - Added historian-gateway-api and slf4j-simple test dependencies    

  - Ingition files/example project commited into the git repo

  - boundaries (bounding values flag):
        asking -1 and +1 point for the period 
        
        we can fake it with two extra
        queries:                                                                              
                                                    
        1. Lower bound: QueryTimeseries with end = start, desc = true, limit = 1 → last point before start                                                                         
        2. Upper bound: QueryTimeseries with start = end, limit = 1 → first point after end   
                                                                                              
        That's 2 extra gRPC calls per query though. The alternative would be to add an        
        includeBoundingValues field to the proto, but that requires changes on the Go backend 
        side too.                                                                             

Still to do:
  - So metadata flows one direction only: 
       Factry → Ignition 
       create tag with metadata in Ignition --/--> is not sent to Factry
                                                                      

  - Integration tests (working on it)                                                                          
      webdev module let you to run scripts on the gateway
       - call the script 
       - check the result (e.g. points arrived to Factry or aggragetation is correct)

  - Null check in FactryGrpcClient.shutdown()                                        
  - Race condition in handleSettingsChange()
  - Silent exception swallowing in doBrowse()                                        
  - Hardcoded timeouts not yet configurable          

# 13/04/2026

changes:
  - use the generated token instead of port/uuid, name, etc.
     remark: - the token will be visible in ignition (plain text store), this might be risk, if somebody has access to ignition file system. However, that is rarely the case. 
             - we could store the token in secret, but it comes with None/Embedded/Referenced radio UI, which is simple just strange 

  -  using Factry image v8.2.0-beta
 
remarks:
  - script to query data from the new factry (it fails)
  - blocked at filling default data to the config page of the historian 
  - new point to measurement silently fails


