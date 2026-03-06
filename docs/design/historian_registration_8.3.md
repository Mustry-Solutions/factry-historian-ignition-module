# Historian Registration in Ignition 8.3+

## Overview

To make the Factry Historian appear in the Gateway UI (**Config → Services → Historians → Create New Historian Profile**), we need to register it as a **HistorianExtensionPoint** with Ignition's Historian Core module.

## Key Architecture Components (Ignition 8.3+)

### 1. Historian Core Module
- Module ID: `com.inductiveautomation.historian`
- Provides: `HistorianManagerImpl` - manages all historians in the gateway
- Provides: `HistorianExtensionPoint` - base class for registering historian types

### 2. Our Module Structure
```
FactryHistorianModule
├── FactryHistorianGatewayHook (our module hook)
├── FactryHistorianExtensionPoint (NEW - needs to be created)
├── FactryHistoryProvider (extends AbstractHistorian)
├── FactryQueryEngine (extends AbstractQueryEngine)
└── FactryStorageEngine (extends AbstractStorageEngine)
```

## What We Need to Implement

### Step 1: Create FactryHistorianExtensionPoint

This class:
- Extends `com.inductiveautomation.historian.gateway.api.HistorianExtensionPoint<FactryHistorianSettings>`
- Provides metadata about our historian type (name, description, icon)
- Has a factory method `createHistorianProvider()` that creates `FactryHistoryProvider` instances

**Constructor requirements:**
```java
public FactryHistorianExtensionPoint(
    String type,           // Unique type ID, e.g., "factry-historian"
    String displayName,    // Display name in UI, e.g., "Factry Historian"
    String description     // Description shown in UI
)
```

**Key method to implement:**
```java
@Override
public Historian<FactryHistorianSettings> createHistorianProvider(
    GatewayContext context,
    DecodedResource<ExtensionPointConfig<HistorianProvider, HistorianSettings>> resource
) throws Exception {
    // Extract settings from resource
    // Create and return new FactryHistoryProvider instance
}
```

### Step 2: Register the Extension Point

In `FactryHistorianGatewayHook.setup()`:
```java
@Override
public void setup(GatewayContext context) {
    // Get the Historian Manager from Historian Core module
    HistorianManagerImpl historianManager = context.getModule("com.inductiveautomation.historian")
        .map(module -> ((HistorianGatewayHook) module).getHistorianManager())
        .orElseThrow(() -> new RuntimeException("Historian Core module not found"));

    // Register our extension point
    FactryHistorianExtensionPoint extensionPoint = new FactryHistorianExtensionPoint();
    historianManager.getExtensionPointCollection().addExtensionPoint(extensionPoint);
}
```

## Important Notes

### 8.1 vs 8.3 Incompatibility
- **Ignition 8.1**: Used old historian API (different package structure, no extension points)
- **Ignition 8.3**: Completely new API with `AbstractHistorian`, `HistorianExtensionPoint`, etc.
- **Our module**: Targets 8.3+ ONLY - not backward compatible

### Built-in Examples in Historian Core
The Historian Core JAR contains several extension point implementations we can reference:
- `SimulatorHistorianExtensionPoint` - Simple test historian
- `CoreHistorianExtensionPoint` - Main SQL-based historian
- `RemoteHistorianExtensionPoint` - Remote historian proxy
- `CsvHistorianExtensionPoint` - CSV file historian

### Extension Point Properties File
Each built-in extension point has a `.properties` file (e.g., `SimulatorHistorianExtensionPoint.properties`) that likely contains:
- Display name
- Description
- Icon path
- Other UI metadata

We may need to create a similar properties file or pass these in the constructor.

## Next Steps

1. **Create `FactryHistorianExtensionPoint` class** in `gateway/src/main/java/io/factry/historian/gateway/`
2. **Modify `FactryHistorianGatewayHook.setup()`** to register the extension point
3. **Test** by checking if "Factry Historian" appears in the "Create New Historian Profile" dropdown in Gateway UI
4. **Verify** that clicking "Create" allows configuring a Factry Historian instance

## References

- Forum Discussion: https://forum.inductiveautomation.com/t/ignition-8-3-building-a-custom-tag-historian-module/100725
- API Package: `com.inductiveautomation.historian.gateway.api`
- Model Package: `com.inductiveautomation.historian.common.model`
