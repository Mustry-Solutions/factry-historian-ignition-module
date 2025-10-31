# Programmatic Historian Creation - Workaround

## Overview

This document describes the workaround implemented to create Factry Historian instances without requiring them to appear in the Gateway UI dropdown.

## Problem

The Historian Core module in Ignition 8.3 hardcodes historian types in a static array (`StandardHistorianCollection.Types`). Third-party modules cannot dynamically register extension points to appear in the "Create New Historian Profile" dropdown because:

1. The `ExtensionPointCollection` is immutable
2. Extension points are passed to the collection at construction time
3. There's no public API to add extension points after initialization

## Solution: Programmatic Historian Creation

Instead of relying on the UI dropdown, we create historian instances programmatically in our module's `startup()` method.

### Implementation

**File**: `gateway/src/main/java/io/factry/historian/gateway/FactryHistorianGatewayHook.java`

```java
@Override
public void startup(LicenseState activationState) {
    // Create default settings
    FactryHistorianSettings settings = new FactryHistorianSettings();
    settings.setProxyUrl("http://factry-proxy:8080");
    settings.setDebugLogging(true);

    // Create the historian provider
    historyProvider = new FactryHistoryProvider(
        gatewayContext,
        "FactryHistorian",
        settings
    );

    // Start the historian
    historyProvider.startup();
}
```

### How It Works

1. **Module Startup**: When the Factry Historian module starts, it creates a historian instance directly
2. **Default Configuration**: Uses hardcoded default settings (proxy URL, timeouts, etc.)
3. **Automatic Start**: The historian starts automatically and is ready to store/query data
4. **Lifecycle Management**: The module manages the historian's lifecycle (startup/shutdown)

### Current Configuration

- **Historian Name**: `FactryHistorian`
- **Proxy URL**: `http://factry-proxy:8080`
- **Timeout**: 5000ms
- **Batch Size**: 100 points
- **Batch Interval**: 5000ms
- **Debug Logging**: Enabled

### Verification

Check the Ignition gateway logs to verify the historian was created:

```bash
docker compose logs ignition | grep -i "factry"
```

Look for these log messages:
- `Creating Factry Historian instance programmatically...`
- `Factry Query Engine initialized with proxy URL: http://factry-proxy:8080`
- `Factry Storage Engine initialized with proxy URL: http://factry-proxy:8080`
- `Factry Historian instance created and started successfully`

### Limitations

1. **No UI Configuration**: Cannot configure the historian through the Gateway web UI
2. **Hardcoded Settings**: Settings are defined in code (requires module rebuild to change)
3. **Single Instance**: Only one historian instance is created per module
4. **No User Control**: Users cannot create/delete/configure historians through the standard UI

### Future Improvements

When Inductive Automation provides guidance on proper extension point registration, we can:

1. Register the extension point properly to appear in the UI dropdown
2. Allow users to create multiple historian instances
3. Provide UI-based configuration
4. Support dynamic enable/disable without module restart

### Next Steps

1. **Contact Inductive Automation**: Ask how third-party modules should register historian extension points
2. **Forum Post**: Create a forum thread asking about the proper registration mechanism
3. **Configuration UI**: Consider creating a custom configuration page in our module to allow settings changes
4. **Persistent Configuration**: Store settings in the Ignition database instead of hardcoding

## Related Documentation

- `docs/historian_registration_8.3.md` - Initial investigation into extension point registration
- `docs/module_update_workflow.md` - Module development and deployment workflow
