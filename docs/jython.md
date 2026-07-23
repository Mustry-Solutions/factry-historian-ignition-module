# Jython Scripting Reference

Copy-pastable scripts for the Ignition Script Console (Designer > Tools > Script Console).
Replace `<historian>` with your historian profile name (e.g., `Factry Historian`) and `<system>` with the gateway system name.

## Supported functions

### system.historian.* (new API, covered by integration tests)
- `system.historian.storeDataPoints` — store data points
- `system.historian.queryRawPoints` — query raw data
- `system.historian.queryAggregatedPoints` — query with aggregation
- `system.historian.queryMetadata` — query measurement metadata
- `system.historian.storeMetadata` — store measurement metadata
- `system.historian.browse` — browse historian hierarchy

### system.tag.* (legacy API, not yet fully supported — see todo.md)
- `system.tag.storeTagHistory` — backfill historical data
- `system.tag.queryTagHistory` — query tag history (raw and aggregated)

---

## system.historian.storeDataPoints

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

## system.historian.queryRawPoints

```python
end = system.date.now()
start = system.date.addHours(end, -1)

ds = system.historian.queryRawPoints(
    paths=["histprov:<historian>:/tag:default/ManualTest/Numeric"],
    startTime=start,
    endTime=end
)

for row in ds:
    print row[0], row[1]
```

## system.historian.queryAggregatedPoints

```python
end = system.date.now()
start = system.date.addHours(end, -1)

ds = system.historian.queryAggregatedPoints(
    paths=["histprov:<historian>:/tag:default/ManualTest/Numeric"],
    startTime=start,
    endTime=end,
    aggregates=["Average", "Minimum", "Maximum"],
    returnSize=1
)

for row in ds:
    print row
```

Supported aggregation modes:

| Mode | Description |
|------|-------------|
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

## system.historian.queryMetadata

```python
ds = system.historian.queryMetadata(
    paths=["histprov:<historian>:/tag:default/ManualTest/Numeric"]
)

for row in ds:
    print row
```

## system.historian.storeMetadata

```python
system.historian.storeMetadata(
    paths=["histprov:<historian>:/sys:<system>:/prov:default:/tag:ManualTest/Numeric"],
    timestamps=[system.date.now()],
    properties=[{"engineeringUnits": "degC", "documentation": "Test tag"}]
)
```

Note: metadata is cached by the module and applied as initial `description`/`attributes` when the measurement is first created in Factry. If the measurement already exists, metadata is cached but not retroactively applied.

## system.historian.browse

```python
# Browse root
results = system.historian.browse("histprov:<historian>:/")
for r in results:
    print r

# Browse deeper
results = system.historian.browse("histprov:<historian>:/tag:default/ManualTest")
for r in results:
    print r
```

## system.tag.queryTagHistory (raw)

```python
end = system.date.now()
start = system.date.addHours(end, -1)

ds = system.tag.queryTagHistory(
    paths=["histprov:<historian>:/tag:default/ManualTest/Numeric"],
    startDate=start,
    endDate=end
)

print "Rows:", ds.getRowCount()
for r in range(ds.getRowCount()):
    print ds.getValueAt(r, 0), ds.getValueAt(r, 1)
```

## system.tag.queryTagHistory (aggregated)

```python
end = system.date.now()
start = system.date.addHours(end, -1)

ds = system.tag.queryTagHistory(
    paths=["histprov:<historian>:/tag:default/ManualTest/Numeric"],
    startDate=start,
    endDate=end,
    aggregationMode="Average",
    returnSize=1
)

print "Average:", ds.getValueAt(0, 1)
```

## system.tag.queryTagHistory (multi-tag)

```python
end = system.date.now()
start = system.date.addHours(end, -1)

ds = system.tag.queryTagHistory(
    paths=[
        "histprov:<historian>:/tag:default/ManualTest/Numeric",
        "histprov:<historian>:/tag:default/ManualTest/Boolean",
        "histprov:<historian>:/tag:default/ManualTest/String"
    ],
    startDate=start,
    endDate=end
)

print "Columns:", [ds.getColumnName(c) for c in range(ds.getColumnCount())]
print "Rows:", ds.getRowCount()
```

## system.tag.storeTagHistory

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
