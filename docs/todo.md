# TODO
     
  [] Factry uses self-signed certificate. We can add to the repo the public part and wired in the validation.

  [] So metadata flows one direction only: 
         Factry → Ignition create tag with metadata in Ignition 
         Other direction: we can't change the metadata, but we can send what we have

  [] Integration tests (working on it)
      webdev module let you to run scripts on the gateway

      call the script
        check the result (e.g. points arrived to Factry or aggragetation is correct)
        Null check in FactryGrpcClient.shutdown()

  [] Race condition in handleSettingsChange()

  [] Silent exception swallowing in doBrowse()

  [] Hardcoded timeouts not yet configurable

  [] Periodic measurement cache refresh to detect deleted measurements
     Factry silently accepts createPoints for non-existent measurement UUIDs (no error returned).
     If a measurement is deleted in Factry while Ignition is writing, data is silently lost.
     Fix: add a periodic cache refresh (e.g. every 30s via the existing scheduler). When deleted
     measurements disappear from the cache, getOrCreateUUID will recreate them on the next store.
     The retry-on-rejection logic in FactryStorageEngine can stay as a safety net for actual errors.