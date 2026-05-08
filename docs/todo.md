# TODO
     





  [] So metadata flows one direction only: 
         Factry → Ignition create tag with metadata in Ignition 
         Other direction: we can't change the metadata, but we can send what we have

  [] Integration tests (working on it)
      webdev module let you to run scripts on the gateway

      call the script
        check the result (e.g. points arrived to Factry or aggragetation is correct)
        Null check in FactryGrpcClient.shutdown()

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


  [] Auto store&forward


## Wannes's items:
  
  [] I've been testing the module a bit today and will list below what I noticed.
  Tag browser in power chart doesnt reflect the asset tree properly and doesnt display the asset properties(which is what is actually linked to the measurement), looks like a flat list of assets currently, should be a tree like in the historian (see screenshot asset-tree.png)
  
  - Related to the next thing, here's a guide to setup a collector that generates some data already
  
  - This other collector and its measurements also doesnt show up in the tag browser (unless I'm doing something wrong, which could very well be)
  
  - Related to above point, calculation collector should also be visible and its calculations, same for internal but that is probably covered by the same logic change
  
  - I think it's best to also set the JWT_SECRET environment variable for the historian in the docker compose file, I had some trouble with that when restarting my docker stack
   
  [+] I added an numeric array tag to the tag browser and populated it using the script console, it appeared in historian but as a separate measurement for each index (see screenshot array-test.png)
  [+] Had a peek in the logs in ignition, something seems wrong with the big value here: Metrics | store: 0 ops, 0 pts (0.0 pts/s), 72879536756468 ms total, 0 errors | raw query: 0 ops, 0 rows, 0 ms total | agg query: 1241 ops, 5292 rows, 11291 ms total
  [+] This is also in the logs but I remember there being an issue with making this field a secret right? The 'token' field in class class io.factry.historian.gateway.FactryHistorianConfig is named such that it implies that it might contain a secret, but the type is not SecretConfig. It is suggested that fields that contain a secret be of type SecretConfig in order to secure the secret. If this field does not contain a secret, use the @NonSecret annotation to indicate that it does not contain a secret and silence this warning.

  Short look at the code, some remarks:
   
  [?] when parsing points return from the historian it looks like you always set quality to Good, the Status we return on a point could potentially be used for this (statusToQuality function in FactryQueryEngine does not seem to be used)



