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
  - new point is not always flushed : 
 
  - if the requests fails for measuremnts -> shows old instead of empty
  - missing point in store and forward

> remark: one 'store and forward' object can serve several historian (or anything else), the pathtag includes the provider

Questions:
  - measurements | calculation | assets in separate folder?
> the protobuf with the new query data is welcome as soon as possible
  - wait for the protobuf or go further? 

# 23/03/2026

Changes:
  + missing point in store and forward
    When the Factry server went down, the gRPC createPoints() call had no deadline and
    blocked indefinitely waiting for a TCP timeout. During this window (2-10 seconds),
    incoming points were lost because the S&F engine couldn't detect the failure fast enough
    to start buffering. Additionally, isEngineUnavailable() always returned false, so S&F
    kept attempting to forward — each attempt blocking again — instead of buffering directly.

    The fix adds a 3-second deadline to createPoints() so failures are detected quickly, and
    tracks connection state via a volatile flag in the gRPC client. When a call fails, the
    flag flips to disconnected and isEngineUnavailable() returns true. This tells S&F to stop
    attempting and buffer all incoming points in the pending queue immediately. A periodic
    connectivity check (every 30s) detects when the server is back and re-enables forwarding.
    This way, only the very first failed call experiences the 3-second timeout; all subsequent
    points go straight to pending with zero delay and zero data loss.


Remarks:
 - pending vs quarantined:
    .points get in pending bucket first
    .it tries to send it periodically
    .if fails, it gets into the quarantined:
      "Imagine a point is malformed or the server rejects it specifically (not a connection   
       issue, but a data issue). If S&F kept retrying it forever in the pending queue, it
       would block all the points behind it — the entire pipeline stalls on one bad record."

    Ergo: quarantined contains malformed points or points while it was not obvious that the engine is not available. We have to send them further periodically. 


 - new point is not always flushed
   That's a classic off-by-one timing issue — Ignition's historian likely sends the       
   previous value when a new change arrives (that's how SourceChangePoint / deadband
   works: it confirms the old value held until the new one arrived). So you're always     
   seeing the previous value because the current one hasn't been "confirmed" yet — it will
   arrive with the next change.

   This is standard Ignition historian behavior, not a bug in our code. The current value 
   gets flushed when the next value change occurs.




Question:
  - what should happen if the tag is removed? should we remove the measurements? 
  - what does the calculation collector mean
