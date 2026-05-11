
# TODO
  [+] use GetMeasurementByFilter to get all the measurements of different collectors
  [+] create the store&forward engine automatic
  [] FactryQueryEngine query grouped by the status=good << let's plot only the good quality data   

  [] manual test
------ 


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
  
  [] TSDB created via API
      "Influx" TSDB UUID: ff6392ac-4872-11f1-bd09-e69c80a3afdc (host: influx:8086, db: factry)
      Created under Root organization. May need to be under "Mustry" org instead.






  
  
   
   
  



