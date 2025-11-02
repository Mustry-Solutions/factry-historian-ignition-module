# Forum Post for Inductive Automation

## Post to add to: https://forum.inductiveautomation.com/t/ignition-8-3-building-a-custom-tag-historian-module/100725/8

---

Thanks @Kevin.Herron for the guidance on using `getExtensionPoints()` - that got us past the first hurdle!

## Current Status

Following Kevin's advice, I've successfully implemented a custom historian module for Ignition 8.3.1 that:

✅ **Extension point registers successfully** - Returns `HistorianExtensionPoint` from `GatewayModuleHook.getExtensionPoints()`
✅ **Historian appears in dropdown** - "Factry Historian" shows up in the "Create New Historian Profile" list
✅ **Historian starts programmatically** - Can create and start historian instance via `AbstractHistorian`
✅ **Query and Storage engines work** - Both `AbstractQueryEngine` and `AbstractStorageEngine` are implemented

## The Problem: Configuration UI

When clicking "Next" after selecting our historian type in the Gateway UI, we get:

```
Configure ¿Factry Historian?
Web UI Component type not found
```

The "Create historian" button is disabled.

## What We've Tried

### Approach 1: Extension Point with Settings
```java
public class FactryHistorianExtensionPoint extends HistorianExtensionPoint<HistorianSettings> {
    public FactryHistorianExtensionPoint() {
        super(TYPE_ID, DISPLAY_NAME, DESCRIPTION);
    }

    @Override
    public Historian<HistorianSettings> createHistorianProvider(
            GatewayContext context,
            DecodedResource<ExtensionPointConfig<HistorianProvider, HistorianSettings>> resource
    ) throws Exception {
        return new FactryHistoryProvider(context, resource.name(), new FactryHistorianSettings());
    }
}
```

We have:
- `FactryHistorianSettings implements HistorianSettings` with proper JavaBean properties
- `.properties` files for localization and field descriptions
- Proper type parameterization

**Result:** Extension point works, historian appears in dropdown, but "Web UI Component type not found" error when configuring.

### Approach 2: Programmatic Historian Creation
Since the UI doesn't work, we tried creating the historian programmatically:

```java
@Override
public void startup(LicenseState activationState) {
    FactryHistorianSettings settings = new FactryHistorianSettings();
    settings.setUrl("http://localhost:8111");

    historyProvider = new FactryHistoryProvider(gatewayContext, "FactryHistorian", settings);
    historyProvider.startup();

    // Historian starts successfully and logs show it's running
}
```

**Result:** Historian runs but **doesn't appear in tag configuration dropdowns**. Tags can't use it because it's not registered with the Historian Manager.

## Key Questions

### 1. UI Component Registration
**Question:** How do third-party modules provide a configuration UI for historian settings in the Gateway?

- Is there a method to override in `HistorianExtensionPoint` to provide a UI component?
- Should we implement a React component? If so, how is it registered?
- Is there documentation for the Web UI component system for historians?

### 2. Historian Registration with Tag System
**Question:** How do programmatically created historians get registered with the tag history system?

We've tried:
- Creating historian via `AbstractHistorian` and calling `startup()`
- Looking for a `HistorianManager.registerHistorian()` method (doesn't exist in public API)
- The historian runs but tags don't see it in dropdowns

**Observations from logs:**
```
[h.p.CoreHistorian] Historian 'corehist' started module-name=Historian Core
[h.p.FactryHistoryProvider] Historian 'FactryHistorian' started module-name=Factry Historian
```

Our historian starts successfully, but tags can't reference it.

### 3. Extension Point API Completeness
**Question:** Is the `HistorianExtensionPoint` API intended to be fully extensible for third-party modules in Ignition 8.3?

- Can third-party modules provide complete historian implementations with UI?
- Or is this API intended only for internal Inductive Automation modules?
- Are there plans to make the UI component registration public?

## What We Need to Complete

Our goal is to create a **production-ready custom historian** that:
1. ✅ Stores tag data to external system via REST API (implemented)
2. ✅ Retrieves historical data from external system (implemented)
3. ❌ **Allows users to configure settings via Gateway UI** (blocked)
4. ❌ **Appears in tag History Provider dropdown** (blocked)

## Module Details

- **Ignition Version:** 8.3.1
- **SDK Version:** 8.1.20
- **Module Structure:** Multi-scope (Gateway, Client, Designer, Common)
- **Architecture:**
  - `FactryHistorianExtensionPoint extends HistorianExtensionPoint<HistorianSettings>`
  - `FactryHistoryProvider extends AbstractHistorian<FactryHistorianSettings>`
  - `FactryQueryEngine extends AbstractQueryEngine`
  - `FactryStorageEngine extends AbstractStorageEngine`
  - `FactryHistorianSettings implements HistorianSettings`

## Code References

Extension point registration (works):
```java
@Override
public List<? extends ExtensionPoint<?>> getExtensionPoints() {
    return Collections.singletonList(new FactryHistorianExtensionPoint());
}
```

Settings class (has proper JavaBean properties):
```java
public class FactryHistorianSettings implements HistorianSettings {
    private String url = "http://localhost:8111";
    private int timeoutMs = 5000;
    private int batchSize = 100;
    // ... getters/setters
}
```

## Request for Guidance

Could someone from Inductive Automation provide guidance on:

1. **How to register a UI component** for historian configuration
2. **How to make a programmatically created historian available** to the tag system
3. **Whether third-party historian modules with full UI integration are supported** in Ignition 8.3
4. **Documentation or examples** for complete third-party historian implementations

If this functionality isn't currently supported in the public API, are there plans to add it in future releases?

## Use Case

We're building a historian that integrates with an external Factry Historian system via REST API. The module needs to be configurable by users (URL, timeout, batch settings) and selectable in tag configuration. This is a common pattern for connecting Ignition to external historian systems.

Any help would be greatly appreciated! We're happy to provide more code samples or clarify anything.

Thanks!

---

## Additional Context (if needed)

The extension point appears in the UI as:
```
¿Factry Historian?
¿External historian for Factry Historian system via REST API?
```

(The ¿ markers appear despite having proper .properties files - minor localization issue we can address later)

When clicking "Next":
```
Configure ¿Factry Historian?
Web UI Component type not found
```

Module loads without errors:
```
[i.f.h.g.FactryHistorianGatewayHook] Factry Historian Module - Setup Complete
[i.f.h.g.FactryHistorianExtensionPoint] Factry Historian Extension Point created
[i.f.h.g.FactryHistorianGatewayHook] getExtensionPoints() called
[h.p.FactryHistoryProvider] Historian 'FactryHistorian' started
```

Everything works except UI configuration and tag system integration.
