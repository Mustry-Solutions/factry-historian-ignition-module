# Manual Test Plan

Manual verification of the Factry Historian module. Work through each test case, recording pass/fail.

## Prerequisites

Before starting, ensure the following are in place:

- **Docker environment running** (`docker compose up -d`) with all containers healthy
- **Module installed** and active in **Config > System > Modules**
- **Factry setup wizard completed** at http://localhost:8000
- **Time series database created** in Factry (Configuration > Time Series Databases)
- **Two historian profiles** created in **Config > Tags > History > Historians**:
  1. **Factry Historian** — direct connection (no S&F engine configured)
  2. **Factry Historian S&F** — with a Store & Forward engine name configured
- **S&F engine created** in **Config > Store & Forward > Engines** matching the name in the S&F historian profile

Historian profiles should both show **Running** status in the Historians list.

---

## Group 1: Tag Creation and Metadata

These tests use the Tag Browser (**Config > Tags > Tag Browser**).

### T1.1 — Create a numeric tag with history

1. Create a Memory Tag: name=`ManualTest/Numeric`, type=Float8
2. Enable history: History Provider = **Factry Historian**, Sample Mode = All Data
3. Write values to the tag (e.g., 10.0, 20.0, 30.0)
4. Open Factry web UI > Measurements

**Expected:** A measurement named `<system>:[default]ManualTest/Numeric` appears in Factry with data type `number`. Data points match the written values.

### T1.2 — Create a boolean tag with history

1. Create a Memory Tag: name=`ManualTest/Boolean`, type=Boolean
2. Enable history: History Provider = **Factry Historian**, Sample Mode = All Data
3. Toggle the value a few times (true/false)

**Expected:** Measurement created with data type `boolean`. Points alternate between true/false.

### T1.3 — Create a string tag with history

1. Create a Memory Tag: name=`ManualTest/String`, type=String
2. Enable history: History Provider = **Factry Historian**, Sample Mode = All Data
3. Write string values (e.g., "hello", "world")

**Expected:** Measurement created with data type `string`. Points contain the correct string values.

### T1.4 — Create an integer tag with history

1. Create a Memory Tag: name=`ManualTest/Integer`, type=Int4
2. Enable history: History Provider = **Factry Historian**, Sample Mode = All Data
3. Write integer values (e.g., 1, 2, 3)

**Expected:** Measurement created with data type `number`. Values stored correctly.

### T1.5 — Tag in a subfolder

1. Create a folder `ManualTest/Subfolder`
2. Create a Memory Tag inside it: `ManualTest/Subfolder/Deep`, type=Float8, history enabled
3. Write a value

**Expected:** Measurement name includes the full path: `<system>:[default]ManualTest/Subfolder/Deep`.

### T1.6 — Rename a tag with history

1. Take `ManualTest/Numeric` from T1.1
2. Rename it to `ManualTest/NumericRenamed`
3. Write a new value

**Expected:** A new measurement is created for the new name. The old measurement remains in Factry (data is not migrated). New values go to the new measurement.

### T1.7 — Move a tag to a different folder

1. Move `ManualTest/Boolean` into `ManualTest/Subfolder/`
2. Write a new value

**Expected:** Same behavior as rename — new measurement created with the new path, old measurement persists.

### T1.8 — Change history provider on a tag

1. Take any tag with history on **Factry Historian**
2. Change its History Provider to **Factry Historian S&F**
3. Write a value

**Expected:** Data now flows through the S&F engine. Check **Status > Store & Forward** for forwarded count. A new measurement may be created in Factry (the historian name is not part of the measurement path).

### T1.9 — Disable and re-enable history

1. Disable history on a tag (History Enabled = false)
2. Write values — these should NOT appear in Factry
3. Re-enable history
4. Write values — these SHOULD appear

**Expected:** No data stored while history is disabled. Data resumes after re-enabling.

### T1.10 — Multiple tags writing simultaneously

1. Create 5 Memory Tags with history enabled, all on **Factry Historian**
2. Write values to all of them in quick succession

**Expected:** All 5 measurements created. Points batched and stored correctly. Check Ignition logs for batch size in `gRPC store succeeded` messages.

---

## Group 2: Jython Scripting

Run these scripts in the **Ignition Script Console** (Designer > Tools > Script Console) or via WebDev endpoints. Replace `<historian>` with your historian profile name (e.g., `Factry Historian`).

### T2.1 — system.tag.queryTagHistory (raw)

```python
end = system.date.now()
start = system.date.addHours(end, -1)

ds = system.tag.queryTagHistory(
    paths=["histprov:<historian>:/tag:<system>/default/ManualTest/Numeric"],
    startDate=start,
    endDate=end
)

print "Rows:", ds.getRowCount()
for r in range(ds.getRowCount()):
    print ds.getValueAt(r, 0), ds.getValueAt(r, 1)
```

**Expected:** Returns the data points stored from Group 1 tests.

### T2.2 — system.tag.queryTagHistory (aggregated)

```python
end = system.date.now()
start = system.date.addHours(end, -1)

ds = system.tag.queryTagHistory(
    paths=["histprov:<historian>:/tag:<system>/default/ManualTest/Numeric"],
    startDate=start,
    endDate=end,
    aggregationMode="Average",
    returnSize=1
)

print "Average:", ds.getValueAt(0, 1)
```

**Expected:** Returns a single row with the average of the stored numeric values.

### T2.3 — Aggregation modes

Run T2.2 with each of these aggregation modes and verify the result makes sense:

| Mode | Expected behavior |
|------|-------------------|
| `Average` | Mean of values |
| `Minimum` | Smallest value |
| `Maximum` | Largest value |
| `Sum` | Sum of all values |
| `Count` | Number of data points |
| `LastValue` | Most recent value |
| `Range` | Max minus Min |
| `Variance` | Statistical variance |
| `StdDev` | Standard deviation |
| `MinMax` | Returns pairs of min and max values |

### T2.4 — system.tag.storeTagHistory

```python
import system

timestamps = [
    system.date.addMinutes(system.date.now(), -3),
    system.date.addMinutes(system.date.now(), -2),
    system.date.addMinutes(system.date.now(), -1),
]

system.tag.storeTagHistory(
    historyprovider="<historian>",
    tagprovider="default",
    paths=["ManualTest/Backfill"],
    values=[[100.0], [200.0], [300.0]],
    qualities=[192, 192, 192],
    timestamps=timestamps
)

print "Store complete"
```

**Expected:** Creates a measurement and stores 3 data points. Verify in Factry that the values and timestamps match.

### T2.5 — system.historian.browse

```python
results = system.historian.browse("histprov:<historian>:/")

for r in results:
    print r
```

**Expected:** Returns a list of browsable nodes. Should show system/provider/tag hierarchy including measurements and assets created in Factry.

### T2.6 — Browse deeper levels

```python
# Browse into the system level
results = system.historian.browse("histprov:<historian>:/tag:<system>/default/ManualTest")

for r in results:
    print r
```

**Expected:** Shows the tags inside the ManualTest folder (Numeric, Boolean, String, etc.).

### T2.7 — system.historian.queryRawPoints

```python
end = system.date.now()
start = system.date.addHours(end, -1)

ds = system.historian.queryRawPoints(
    paths=["histprov:<historian>:/tag:<system>/default/ManualTest/Numeric"],
    startTime=start,
    endTime=end
)

for row in ds:
    print row[0], row[1]
```

**Expected:** Returns raw data points with timestamps and values.

### T2.8 — system.historian.queryAggregatedPoints

```python
end = system.date.now()
start = system.date.addHours(end, -1)

ds = system.historian.queryAggregatedPoints(
    paths=["histprov:<historian>:/tag:<system>/default/ManualTest/Numeric"],
    startTime=start,
    endTime=end,
    aggregates=["Average", "Minimum", "Maximum"],
    returnSize=1
)

for row in ds:
    print row
```

**Expected:** Returns aggregated values for each requested aggregate.

### T2.9 — system.historian.queryMetadata

```python
ds = system.historian.queryMetadata(
    paths=["histprov:<historian>:/tag:<system>/default/ManualTest/Numeric"]
)

for row in ds:
    print row
```

**Expected:** Returns metadata about the measurement (data type, status, name).

### T2.10 — system.historian.storeDataPoints

```python
from com.inductiveautomation.historian.common.model import DataPoint

dp = DataPoint(
    "histprov:<historian>:/sys:<system>:/prov:default:/tag:ManualTest/ScriptStore",
    42.0,
    system.date.now(),
    192
)

system.historian.storeDataPoints([dp])
print "Stored"
```

**Expected:** Creates a measurement and stores the data point. Verify in Factry.

### T2.11 — system.historian.storeMetadata

```python
system.historian.storeMetadata(
    paths=["histprov:<historian>:/sys:<system>:/prov:default:/tag:ManualTest/Numeric"],
    timestamps=[system.date.now()],
    properties=[{"engineeringUnits": "degC", "documentation": "Test tag"}]
)
```

**Expected:** Metadata stored (or gracefully handled — Factry manages metadata on its platform, the module may no-op this).

### T2.12 — Multi-tag query

```python
end = system.date.now()
start = system.date.addHours(end, -1)

ds = system.tag.queryTagHistory(
    paths=[
        "histprov:<historian>:/tag:<system>/default/ManualTest/Numeric",
        "histprov:<historian>:/tag:<system>/default/ManualTest/Boolean",
        "histprov:<historian>:/tag:<system>/default/ManualTest/String"
    ],
    startDate=start,
    endDate=end
)

print "Columns:", [ds.getColumnName(c) for c in range(ds.getColumnCount())]
print "Rows:", ds.getRowCount()
```

**Expected:** Returns a DataSet with t_stamp + 3 value columns. All tags queried in a single call.

---

## Group 3: Store & Forward

These tests validate data buffering during outages. Use the **Factry Historian S&F** profile.

### T3.1 — Normal S&F operation

1. Create a tag with history on **Factry Historian S&F**
2. Write values
3. Check **Status > Store & Forward** page

**Expected:** Data flows through S&F. Forwarded count increases. Pending and quarantined counts stay at 0.

### T3.2 — Factry goes down — data buffered

1. Ensure a tag is actively writing to **Factry Historian S&F**
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

### T3.7 — Direct historian during Factry outage (no S&F)

1. Ensure a tag is writing to **Factry Historian** (the profile WITHOUT S&F)
2. Stop Factry: `docker compose stop historian`
3. Write values

**Expected:** Data points are LOST — there is no S&F buffer. Ignition logs show gRPC errors. This confirms why the S&F profile is important for production.

### T3.8 — S&F engine metrics

During normal operation with S&F:
1. Check Ignition logs for the 30-second metrics line

**Expected:** Metrics show store operations, point counts, points/sec, and query statistics:
```
Metrics | store: N ops, N pts (X.X pts/s), ... | raw query: ... | agg query: ...
```

---

## Group 4: Browsing and Visualization

### T4.1 — Browse measurements in Power Chart

1. Open a Perspective session with a Power Chart
2. Click the tag browse icon
3. Expand the Factry Historian provider

**Expected:** Shows a hierarchy: system > provider > tag folders > tag leaves. Measurements from Group 1 are visible.

### T4.2 — Browse assets

1. Create an asset in Factry web UI with measurements attached
2. Browse the historian in Power Chart

**Expected:** Assets appear as a separate category alongside Measurements.

### T4.3 — Plot data in Power Chart

1. Select a numeric measurement from the browse tree
2. Set a time range that covers the data from Group 1

**Expected:** Chart displays historical data points. Zooming in/out updates the query.

---

## Test Results

| Test | Pass/Fail | Notes |
|------|-----------|-------|
| T1.1 | | |
| T1.2 | | |
| T1.3 | | |
| T1.4 | | |
| T1.5 | | |
| T1.6 | | |
| T1.7 | | |
| T1.8 | | |
| T1.9 | | |
| T1.10 | | |
| T2.1 | | |
| T2.2 | | |
| T2.3 | | |
| T2.4 | | |
| T2.5 | | |
| T2.6 | | |
| T2.7 | | |
| T2.8 | | |
| T2.9 | | |
| T2.10 | | |
| T2.11 | | |
| T2.12 | | |
| T3.1 | | |
| T3.2 | | |
| T3.3 | | |
| T3.4 | | |
| T3.5 | | |
| T3.6 | | |
| T3.7 | | |
| T3.8 | | |
| T4.1 | | |
| T4.2 | | |
| T4.3 | | |
