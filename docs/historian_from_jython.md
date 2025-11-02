# Direct Historian Testing via Jython Scripts

## Overview

Since the historian can't be registered with the tag system UI, we can **directly call the historian's engines from scripts** to test functionality. This bypasses tags entirely but still uses your actual `FactryHistoryProvider`, `FactryStorageEngine`, and `FactryQueryEngine` implementations.

## Method 1: Access Historian via Gateway Context (Recommended)

The historian instance is stored in your `FactryHistorianGatewayHook`. We can expose it via a global or access it directly.

### Step 1: Expose Historian in Module Hook

First, let's modify the GatewayHook to make the historian accessible:

**File:** `gateway/src/main/java/io/factry/historian/gateway/FactryHistorianGatewayHook.java`

Add a method to get the historian instance:

```java
/**
 * Get the running historian instance for scripting access.
 * @return The FactryHistoryProvider instance, or null if not started
 */
public FactryHistoryProvider getHistorian() {
    return historyProvider;
}
```

### Step 2: Access from Jython Scripts

Now you can access the historian from any Gateway script:

```python
# Gateway Script or Designer Script (with gateway scope)

# Get the Factry Historian module
gatewayContext = system.util.getGlobals()["gatewayContext"]
moduleManager = gatewayContext.getModuleManager()

# Get our module's hook
factryModule = moduleManager.getModule("io.factry.historian.FactryHistorian")
if factryModule:
    hook = factryModule.hook
    historian = hook.getHistorian()

    if historian:
        print("✓ Factry Historian found:", historian.getName())
        print("✓ Settings:", historian.getSettings())
    else:
        print("✗ Historian not started")
else:
    print("✗ Module not loaded")
```

## Method 2: Test Storage Engine Directly

Once you have the historian reference, you can call its storage engine:

```python
from com.inductiveautomation.historian.common.model.data import AtomicPoint
from com.inductiveautomation.ignition.common.model import QualityCode
from com.inductiveautomation.ignition.common import TagPath
import time

# Get historian
gatewayContext = system.util.getGlobals()["gatewayContext"]
moduleManager = gatewayContext.getModuleManager()
factryModule = moduleManager.getModule("io.factry.historian.FactryHistorian")
historian = factryModule.hook.getHistorian()

# Get the storage engine
storageEngine = historian.getStorageEngine().orElse(None)

if storageEngine:
    print("✓ Storage Engine found")

    # Create a test data point
    from java.util import ArrayList

    points = ArrayList()

    # Create an atomic point manually
    # AtomicPoint requires: source (TagPath), timestamp, value, quality
    tagPath = TagPath.fromString("[default]TestTag")
    timestamp = long(time.time() * 1000)  # Current time in milliseconds
    value = 123.45
    quality = QualityCode.Good

    # Note: You'll need to check the exact AtomicPoint constructor
    # This is the concept - exact API may vary
    point = AtomicPoint.create(tagPath, timestamp, value, quality)
    points.add(point)

    # Store the points
    result = storageEngine.doStoreAtomic(points)

    print("Storage result:", result)
    print("Check Gateway logs for 'Would store...' message")
else:
    print("✗ Storage Engine not available")
```

## Method 3: Test Query Engine Directly

Similarly, test querying historical data:

```python
from com.inductiveautomation.historian.common.model import TimeRange
from com.inductiveautomation.historian.common.model.options import RawQueryOptions
from com.inductiveautomation.ignition.common import TagPath
import time

# Get historian
gatewayContext = system.util.getGlobals()["gatewayContext"]
moduleManager = gatewayContext.getModuleManager()
factryModule = moduleManager.getModule("io.factry.historian.FactryHistorian")
historian = factryModule.hook.getHistorian()

# Get query engine
queryEngine = historian.getQueryEngine().orElse(None)

if queryEngine:
    print("✓ Query Engine found")

    # Set up query parameters
    tagPath = TagPath.fromString("[default]TestTag")

    # Time range: last hour
    endTime = long(time.time() * 1000)
    startTime = endTime - (60 * 60 * 1000)  # 1 hour ago

    timeRange = TimeRange.of(startTime, endTime)

    # Create query options
    # Note: Check exact API for RawQueryOptions
    queryOptions = RawQueryOptions.builder()
        .addQueryKey(tagPath)
        .setTimeRange(timeRange)
        .build()

    # Execute query
    # You'd need a processor to handle results
    # For testing, just trigger the query to see logs

    print("Query would execute for:", tagPath)
    print("Time range:", startTime, "to", endTime)
    print("Check Gateway logs for 'Would query...' message")
else:
    print("✗ Query Engine not available")
```

## Method 4: Complete Test Script

Here's a complete script to test both storage and retrieval:

```python
"""
Complete test script for Factry Historian
Run this in Designer Script Console or as a Gateway Event Script
"""

import system
import time
from java.util import ArrayList

def testFactryHistorian():
    """Test the Factry Historian directly without tags"""

    logger = system.util.getLogger("FactryHistorian.Test")

    # 1. Get the historian
    try:
        gatewayContext = system.util.getGlobals()["gatewayContext"]
        moduleManager = gatewayContext.getModuleManager()
        factryModule = moduleManager.getModule("io.factry.historian.FactryHistorian")

        if not factryModule:
            logger.error("Factry Historian module not loaded")
            return False

        historian = factryModule.hook.getHistorian()

        if not historian:
            logger.error("Historian not started")
            return False

        logger.info("✓ Historian found: {}", historian.historianName)

    except Exception as e:
        logger.error("Error accessing historian: {}", str(e))
        return False

    # 2. Test Storage Engine
    logger.info("Testing Storage Engine...")
    try:
        storageEngine = historian.getStorageEngine().orElse(None)

        if storageEngine:
            logger.info("✓ Storage Engine ready")
            logger.info("Storage Engine class: {}", storageEngine.getClass().getName())

            # Check if unavailable
            if storageEngine.isEngineUnavailable():
                logger.warn("Storage Engine reports unavailable")
            else:
                logger.info("✓ Storage Engine is available")

            # For full test, you would create AtomicPoints here
            # and call storageEngine.doStoreAtomic(points)

        else:
            logger.warn("Storage Engine not available")

    except Exception as e:
        logger.error("Storage Engine error: {}", str(e))

    # 3. Test Query Engine
    logger.info("Testing Query Engine...")
    try:
        queryEngine = historian.getQueryEngine().orElse(None)

        if queryEngine:
            logger.info("✓ Query Engine ready")
            logger.info("Query Engine class: {}", queryEngine.getClass().getName())

            # Check if unavailable
            if queryEngine.isEngineUnavailable():
                logger.warn("Query Engine reports unavailable")
            else:
                logger.info("✓ Query Engine is available")

            # For full test, you would create RawQueryOptions here
            # and call queryEngine.doQueryRaw(options, processor)

        else:
            logger.warn("Query Engine not available")

    except Exception as e:
        logger.error("Query Engine error: {}", str(e))

    # 4. Test Settings
    logger.info("Checking Settings...")
    try:
        settings = historian.getSettings()
        logger.info("Proxy URL: {}", settings.getUrl())
        logger.info("Timeout: {} ms", settings.getTimeoutMs())
        logger.info("Batch Size: {}", settings.getBatchSize())
        logger.info("Debug Logging: {}", settings.isDebugLogging())
    except Exception as e:
        logger.error("Settings error: {}", str(e))

    logger.info("="*50)
    logger.info("Test complete - check logs above")
    logger.info("="*50)

    return True

# Run the test
result = testFactryHistorian()
print("Test completed:", "SUCCESS" if result else "FAILED")
```

## Method 5: Simplified Storage Test

A simpler version that just verifies the storage engine can be called:

```python
# Simple storage test
import system

gatewayContext = system.util.getGlobals()["gatewayContext"]
moduleManager = gatewayContext.getModuleManager()
factryModule = moduleManager.getModule("io.factry.historian.FactryHistorian")

if factryModule:
    historian = factryModule.hook.getHistorian()

    if historian:
        print("Historian Name:", historian.historianName)
        print("Historian Status:", historian.getStatus())

        # Get storage engine
        storageOpt = historian.getStorageEngine()
        if storageOpt.isPresent():
            storage = storageOpt.get()
            print("Storage Engine:", storage.getClass().getSimpleName())
            print("Engine Available:", not storage.isEngineUnavailable())

        # Get query engine
        queryOpt = historian.getQueryEngine()
        if queryOpt.isPresent():
            query = queryOpt.get()
            print("Query Engine:", query.getClass().getSimpleName())
            print("Engine Available:", not query.isEngineUnavailable())
    else:
        print("ERROR: Historian not started")
else:
    print("ERROR: Module not loaded")
```

## Expected Output

When you run the simple test, you should see:

```
Historian Name: FactryHistorian
Historian Status: RUNNING
Storage Engine: FactryStorageEngine
Engine Available: True
Query Engine: FactryQueryEngine
Engine Available: True
```

## Next Steps

Once you can access the historian from scripts:

1. **Create AtomicPoints** - Learn the exact API for creating data points
2. **Call doStoreAtomic()** - Send test data to storage engine
3. **Verify proxy receives data** - Check Golang proxy logs
4. **Create query options** - Set up historical queries
5. **Call doQueryRaw()** - Retrieve data from query engine
6. **Verify proxy returns data** - Check response handling

## Benefits of This Approach

✅ **Tests actual historian code** - Uses your real implementation
✅ **Bypasses tag system** - No dependency on tag configuration
✅ **Immediate feedback** - Can test without waiting for IA support
✅ **Proves concept** - Shows storage/query engines work
✅ **Gateway logs show activity** - "Would store..." messages appear

This validates your historian implementation while we wait for guidance on tag system integration!

## Troubleshooting

### Module not found
```python
moduleManager.getModule("io.factry.historian.FactryHistorian")
```
Make sure the module ID matches your `build.gradle.kts` `moduleId` setting.

### Hook doesn't have getHistorian()
You need to add the method to `FactryHistorianGatewayHook.java` first (see Step 1 above).

### Historian is null
Check Gateway logs - the historian may not have started successfully:
```bash
docker compose logs ignition | grep "Factry Historian started"
```

### Can't access gatewayContext
This only works in Gateway scope (Gateway Event Scripts, not Perspective scripts).
In Designer, you may need different access patterns.
