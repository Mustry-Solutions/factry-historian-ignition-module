# Weekly meetings

# 09/03/2026 'Collect Tag Changes (Collector)'

This is the first version of sending tag changes to the factry collector.

Prior work:
  - scaffolding ignition module
  - design document
  - installing module, creating historian, assigning to tag
   
Recently:
  - implementing gRPC collector implementation for the module 
  - tidy up documentation 
  - some clarification

Check try-out.md.

> this is in the 'Milestone 1: Proof of Concept' (proposed milestones)

known issue:
  - store and forward is not yet added (in progress)
  - edit settings are not possible in ignition, always recreate historian


Questions:
  - for provider we have to wait, but browsing measurements can be done. Only measurements?
  - docker-compose with everything (ignition + factry historian + dbs )?
  - how do you want to install the module and ignition (docker?)
  - documentation
      design document and other assets there
      signing the module
      try-out


# 16/03/2026 


