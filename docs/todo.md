
# TODO
  [+] manual test
      getting realfakedata from here
      https://docs.factry.io/installing-factry-historian-using-docker-for-testing-purposes

     
     - more tests: both manual and automated      
     - two ignitions with two different collectors  ( same collector doesn't defi
     - ne unique name: coll1/default/var1 can come form two different ignition)
     - two ignitions, one with factry historian, one with remote historian   

  [] So metadata flows one direction only: 
         Factry → Ignition create tag with metadata in Ignition 
         Other direction: we can't change the metadata, but we can send what we have

  [] Setup automation (setup-factry.sh)
      Status: script updated to use PascalCase fields (UUID, Name, Status) matching Factry API.
      BLOCKED: Collector creation via POST /api/collectors fails with "record not found".
      Root cause: likely organization context issue — two orgs exist ("Root organization" and
      "Mustry") and the API can't resolve which org to use for the collector.
      Next steps:
        - Figure out how to set org context (header? user association? TSDB ownership?)
        - Or create collector manually in Factry UI, then script only needs to generate token
        - Once collector exists: run setup-historians.sh with token, restart Ignition, run tests
    - easier setup: combination of scripts and manual step 
  
  [] TSDB created via API
      "Influx" TSDB UUID: ff6392ac-4872-11f1-bd09-e69c80a3afdc (host: influx:8086, db: factry)
      Created under Root organization. May need to be under "Mustry" org instead.

## Wannnes's list

Condensed below, more detail and minor items are in the attached document.
1. Writes to new tag during historian outage(or not reachable) are silently dropped, it tries to create the new measurement a couple of times but ends up dropping the point
2. When configuring a new historian in ignition and leaving "Use TLS" unchecked, after submitting it gets enabled anyway
3. Editing an array tag in ignition only sends the changes indexes to historian, for example changing [1,2,3,4] to [1,2,4,5] gets sent as the value [4,5]
4. Not sure how far implementation ever got for this but I couldn't get metadata or engineering specs to work when creating new tags (as it only is supported on creation)
5. Version is on 1.0.7 at the moment, would be good to make it will be 1.0.0 when we do the first release
6. TLS only connects with "Skip TLS Verification" on, which leaves it unauthenticated. Confirmed against a deployed historian using a normal Let's Encrypt cert: the module trusts only its bundled certificate and rejects everything else, so verification always fails. It should also trust the system/public CAs alongside ours.
7. Querying the raw history of a string tag throws an error and aborts the whole query, so a query mixing string and numeric tags fails entirely instead of just skipping the string one.
8. The Batch size and Batch interval configurations do not seem to be used, so unless I'm wrong could be better to remove those





  
  
   
   
  



