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
 [+] Editing settings
 [+] Store and forward
 [+] bug: creating two measurements, because it didn't wait for the first
 
> (Milestone 2: Historian Collector Full implementation < is this enough?)

 [+] preliminary protobuf + emulator
 [+] Browsing measurements from the emulator
 



# 23/03/2026
