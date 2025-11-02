# Workaround: Configuring Tags with FactryHistorian via Scripts

## Problem

The `FactryHistorian` does not appear in the History Provider dropdown in the Tag Editor because:
- Programmatically created historians are not automatically registered with Ignition's Historian Manager
- The Historian Extension Point API doesn't support UI component registration for third-party modules
- The tag configuration UI only shows historians managed by the Historian Core module

## Solution: Use Gateway Scripts

You can configure tags to use the FactryHistorian programmatically using Ignition's scripting API.

## Method 1: Using Script Console (Designer)

1. Open Designer
2. Go to **Tools** → **Script Console**
3. Run this script to configure a tag with FactryHistorian:

```python
# Configure a single tag to use FactryHistorian
tagPath = "[default]MyTag"

tagConfig = {
    "historyEnabled": True,
    "historicalDeadband": 0.0,
    "historicalDeadbandStyle": "Absolute",
    "historicalScanClass": "Default Historical",
    "historyProvider": "FactryHistorian",  # Our custom historian
    "historyMaxAge": 0,
    "historyMaxAgeUnits": "Month",
    "historySampleRate": 0,
    "historySampleRateUnits": "Second"
}

# Apply configuration
system.tag.configure(tagPath, tagConfig)

print("Tag configured with FactryHistorian")
```

## Method 2: Batch Configuration Script

Configure multiple tags at once:

```python
# Configure multiple tags
tagPaths = [
    "[default]TestTag1",
    "[default]TestTag2",
    "[default]Sensor/Temperature"
]

for tagPath in tagPaths:
    tagConfig = {
        "historyEnabled": True,
        "historyProvider": "FactryHistorian",
        "historicalScanClass": "Default Historical"
    }

    system.tag.configure(tagPath, tagConfig)
    print("Configured: " + tagPath)

print("All tags configured")
```

## Method 3: Create Tag WITH History (Script)

Create a new tag already configured with FactryHistorian:

```python
# Create a new Memory Tag with Factry Historian
tagPath = "[default]NewTestTag"

tagConfig = {
    "name": "NewTestTag",
    "tagType": "AtomicTag",
    "dataType": "Float8",
    "value": 100.0,
    "historyEnabled": True,
    "historyProvider": "FactryHistorian",
    "historicalDeadband": 0.0,
    "historicalDeadbandStyle": "Absolute",
    "historicalScanClass": "Default Historical"
}

system.tag.addTag(parentPath="[default]", name="NewTestTag", tagType="AtomicTag", attributes=tagConfig)

print("Tag created with FactryHistorian")
```

## Method 4: Gateway Event Script (Automatic)

Create a Gateway Event script that runs on startup to automatically configure tags:

1. Go to Gateway web interface
2. Navigate to **Config** → **Scripting** → **Gateway Event Scripts**
3. Create a **Startup** script:

```python
# Gateway Startup Script - Configure tags with FactryHistorian

import system

def configureTags():
    """Configure specified tags to use FactryHistorian"""

    tagsToConfig = [
        "[default]Production/LineSpeed",
        "[default]Production/Temperature",
        "[default]Production/Pressure"
    ]

    historianConfig = {
        "historyEnabled": True,
        "historyProvider": "FactryHistorian",
        "historicalDeadband": 0.0,
        "historicalDeadbandStyle": "Absolute",
        "historicalScanClass": "Default Historical"
    }

    logger = system.util.getLogger("FactryHistorian.AutoConfig")

    for tagPath in tagsToConfig:
        try:
            system.tag.configure(tagPath, historianConfig)
            logger.info("Configured tag: " + tagPath)
        except Exception as e:
            logger.error("Failed to configure " + tagPath + ": " + str(e))

    logger.info("FactryHistorian auto-configuration complete")

# Run configuration
configureTags()
```

## Method 5: Verify Current Historian

Check which historian a tag is using:

```python
# Read tag configuration
tagPath = "[default]MyTag"

config = system.tag.getConfiguration(tagPath)[0]

print("Tag:", tagPath)
print("History Enabled:", config["historyEnabled"])
print("History Provider:", config.get("historyProvider", "None"))
```

## Verification

After configuring a tag, verify it's using FactryHistorian:

### Via Script Console:

```python
tagPath = "[default]MyTag"
config = system.tag.getConfiguration(tagPath)[0]

if config.get("historyProvider") == "FactryHistorian":
    print("SUCCESS: Tag is using FactryHistorian")
else:
    print("ERROR: Tag is using:", config.get("historyProvider", "None"))
```

### Via Gateway Logs:

Change the tag value and check logs for storage activity:

```bash
docker compose logs ignition -f | grep -i "factry\|store"
```

Expected output:
```
Would store 1 atomic points to http://localhost:8111/collector
```

## Testing the POC

### Step 1: Create and Configure Tag via Script

```python
# Create test tag
tagConfig = {
    "name": "FactryTest",
    "tagType": "AtomicTag",
    "dataType": "Float8",
    "value": 100.0,
    "historyEnabled": True,
    "historyProvider": "FactryHistorian",
    "historicalScanClass": "Default Historical"
}

system.tag.addTag(parentPath="[default]", name="FactryTest", tagType="AtomicTag", attributes=tagConfig)
```

### Step 2: Change Tag Value

```python
# Write value to trigger collection
system.tag.writeBlocking(["[default]FactryTest"], [150.0])
```

### Step 3: Check Gateway Logs

```bash
docker compose logs ignition -f | grep "FactryTest\|store"
```

### Step 4: Add to PowerChart

1. Create a Perspective or Vision view
2. Add Power Chart component
3. Add pen: `[default]FactryTest`
4. Check logs for query activity

## Complete POC Setup Script

Run this in Designer Script Console to set up everything for POC:

```python
import system

def setupFactryPOC():
    """Complete POC setup for Factry Historian"""

    logger = system.util.getLogger("FactryHistorian.POC")

    # 1. Create collector test tag (for storage testing)
    collectorTag = {
        "name": "FactryCollectorTest",
        "tagType": "AtomicTag",
        "dataType": "Float8",
        "value": 50.0,
        "historyEnabled": True,
        "historyProvider": "FactryHistorian",
        "historicalScanClass": "Default Historical",
        "historicalDeadband": 0.0
    }

    try:
        system.tag.addTag(parentPath="[default]", name="FactryCollectorTest", tagType="AtomicTag", attributes=collectorTag)
        logger.info("Created: [default]FactryCollectorTest")
    except:
        # Tag might already exist, configure it
        system.tag.configure("[default]FactryCollectorTest", {"historyProvider": "FactryHistorian", "historyEnabled": True})
        logger.info("Configured existing: [default]FactryCollectorTest")

    # 2. Create query test tag (for provider testing)
    queryTag = {
        "name": "FactryQueryTest",
        "tagType": "AtomicTag",
        "dataType": "Float8",
        "value": 100.0,
        "historyEnabled": True,
        "historyProvider": "FactryHistorian",
        "historicalScanClass": "Default Historical"
    }

    try:
        system.tag.addTag(parentPath="[default]", name="FactryQueryTest", tagType="AtomicTag", attributes=queryTag)
        logger.info("Created: [default]FactryQueryTest")
    except:
        system.tag.configure("[default]FactryQueryTest", {"historyProvider": "FactryHistorian", "historyEnabled": True})
        logger.info("Configured existing: [default]FactryQueryTest")

    # 3. Write some test values to trigger collection
    system.tag.writeBlocking(["[default]FactryCollectorTest"], [75.0])
    logger.info("Wrote test value to FactryCollectorTest")

    print("="*50)
    print("POC SETUP COMPLETE")
    print("="*50)
    print("Tags created:")
    print("  - [default]FactryCollectorTest (for storage testing)")
    print("  - [default]FactryQueryTest (for query testing)")
    print("")
    print("Next steps:")
    print("  1. Check Gateway logs for 'Would store...' messages")
    print("  2. Add FactryQueryTest to a PowerChart")
    print("  3. Change FactryCollectorTest value to test collection")
    print("  4. Start your Golang proxy server on http://localhost:8111")
    print("="*50)

# Run setup
setupFactryPOC()
```

## Troubleshooting

### "historyProvider not found" Error

The historian may not be running. Check Gateway logs:
```bash
docker compose logs ignition | grep "Factry Historian started successfully"
```

### Tags Not Recording to Factry Historian

1. Verify tag configuration:
```python
config = system.tag.getConfiguration("[default]MyTag")[0]
print(config.get("historyProvider"))
```

2. Check if history is enabled:
```python
print(config.get("historyEnabled"))
```

3. Verify historian is running in Gateway logs

### No Logs Showing Storage Activity

The historian may be running but debug logging might not show. Check the `FactryHistorianSettings` debug flag is enabled (it should be by default in the module).

## Alternative: JSON Import

You can also import tag configuration from JSON:

```json
{
  "name": "FactryTestTag",
  "tagType": "AtomicTag",
  "dataType": "Float8",
  "value": 100.0,
  "historyEnabled": true,
  "historyProvider": "FactryHistorian",
  "historicalScanClass": "Default Historical"
}
```

Use Designer → Tags → Import Tags from JSON

## Summary

While the FactryHistorian doesn't appear in the UI dropdown, you can fully configure and use it via:
- ✅ Script Console (Designer)
- ✅ Gateway Event Scripts
- ✅ Tag JSON Import
- ✅ Programmatic tag creation

This is a temporary workaround until Inductive Automation provides a way to register UI components for third-party historians.
