# Manual Test Plan


## Prerequisites

- **Docker environment running** (`docker compose up -d`) with all containers healthy
- **Module installed** and active in **Config > System > Modules**
- **Factry setup wizard completed** at http://localhost:8000
- **Time series database created** in Factry (Configuration > Time Series Databases)
- **Historian profile** created in **Config > Tags > History > Historians**:
- **Factry Historian** — the module automatically creates an S&F engine on startup

The historian profile should show **Running** status in the Historians list.

---

## Overview of the test environment

![Test Overview](manual_test.excalidraw.svg)


## Group 1: Tag Creation and Metadata

These tests use the Tag Browser in the Designer (**Config > Tags > Tag Browser**).
Open your designer and create tags with historian and parameters:
   [] T1.1 — name=`ManualTest/ff1`, type=Float8, write values 10.0, 20.0, 30.0, etc..
   [] T1.2 — name=`ManualTest/bb1`, type=Boolean, toggle a few times
   [] T1.3 — name=`ManualTest/ss1`, type=String, write "hello", "world"
   [] T1.4 — name=`ManualTest/ii1`, type=Int4, write 1, 2, 3
   [] T1.5 — name=`ManualTest/Subfolder/Deep`, type=Float8, verify full path in Factry
   [] T1.6 — Rename `ManualTest/ff1` → `ManualTest/ff1Renamed`, write new value, verify new measurement created
   [] T1.7 — Move `ManualTest/bb1` into `ManualTest/Subfolder/`, write new value, verify new measurement
   [] T1.8 — Write a value, check Status > Store & Forward, verify data flows through S&F engine
   [] T1.9 — Disable history on a tag, write values (should NOT appear), re-enable, write again (should appear)
   [] T1.10 — Create 5 tags with history, write to all quickly, verify all measurements created and batched

Check the results in Factry Measurements and on PowerChart


---

> **Jython scripting examples** (copy-pastable code for Script Console) moved to [docs/jython.md](jython.md).
> All `system.historian.*` functions are covered by integration tests.

---

## Group 3: Store & Forward

These tests validate data buffering during outages. S&F is always enabled — the module automatically creates an S&F engine matching the historian profile name.

### T3.1 — Normal S&F operation

1. Create a tag with history on **Factry Historian**
2. Write values
3. Check **Status > Store & Forward** page

**Expected:** Data flows through S&F. Forwarded count increases. Pending and quarantined counts stay at 0. The S&F engine name matches the historian profile name.

### T3.2 — Factry goes down — data buffered

1. Ensure a tag is actively writing to **Factry Historian**
2. Stop the Factry historian container: `docker compose stop historian`
3. Continue writing values to the tag (at least 10-20 values)
4. Check **Status > Store & Forward**

**Expected:**
- Pending count increases as data is buffered
- Quarantined count stays at 0 (or briefly increases then returns to 0 thanks to auto-retry)
- Ignition logs show `UNAVAILABLE` errors, then `Storage engine unavailable, S&F will buffer`
- The historian status in Config shows an error state

### T3.3 — Factry comes back — data forwarded

1. With data buffered from T3.2, restart Factry: `docker compose start historian`
2. Complete the setup wizard again if needed (see memory note about license)
3. Watch **Status > Store & Forward**

**Expected:**
- Within 30 seconds, the module detects the connection is restored
- Pending count drains to 0 as buffered data is forwarded
- Ignition logs show `Factry server is reachable again`
- Verify in Factry that all buffered data points arrived with correct timestamps

### T3.4 — Verify no data loss

1. Before T3.2, note the last value and timestamp stored in Factry
2. After T3.3 completes, query the full time range in Factry

**Expected:** All values written during the outage are present. No gaps in the timeline (within the batch interval resolution).

### T3.5 — Historian status transitions

Monitor the historian status in **Config > Tags > History > Historians** during the T3.2/T3.3 cycle:

| Phase | Expected Status |
|-------|----------------|
| Normal operation | Running |
| After Factry stops | Error / Faulted (within 30s) |
| After Factry restarts | Running (within 30s of detection) |

### T3.6 — Quarantine behavior

1. Stop Factry: `docker compose stop historian`
2. Write a large batch of values (50+)
3. Wait 2-3 minutes (let S&F attempt forwarding multiple times)
4. Check **Status > Store & Forward**

**Expected:** Quarantined records should be automatically moved back to pending by the module's 30-second retry task. The quarantine count may briefly increase but should return to 0.

### T3.7 — S&F engine auto-creation

1. Delete the S&F engine from **Config > Store & Forward > Engines**
2. Restart the Ignition gateway: `docker compose restart ignition`
3. Check **Config > Store & Forward > Engines**

**Expected:** The module automatically re-creates the S&F engine on startup. The engine name matches the historian profile name.

---

## Group 4: Browsing and Visualization

### T4.1 — Browse measurements in Power Chart

1. Open a Perspective session with a Power Chart
2. Click the tag browse icon
3. Expand the Factry Historian provider

**Expected:** Shows a hierarchy: Measurements/Assets > collector name > provider > tag folders > tag leaves. Measurements from Group 1 are visible.

### T4.2 — Browse assets

1. Create an asset in Factry web UI with measurements attached
2. Browse the historian in Power Chart

**Expected:** Assets appear as a separate category alongside Measurements.

### T4.3 — Plot data in Power Chart

1. Select a numeric measurement from the browse tree
2. Set a time range that covers the data from Group 1

**Expected:** Chart displays historical data points. Zooming in/out updates the query.



