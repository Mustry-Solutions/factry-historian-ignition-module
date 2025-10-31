# Questions for Inductive Automation

This document contains questions to ask on the Inductive Automation forums regarding third-party historian module development in Ignition 8.3+.

## Context

We are developing a custom historian module for Ignition 8.3 that integrates with an external Factry Historian system via REST API. We've successfully implemented the historian functionality using the `HistorianExtensionPoint` API, but encountered limitations with the configuration UI.

## Current Implementation Status

✅ **Working:**
- Module loads successfully in Ignition 8.3.1
- `FactryHistorianExtensionPoint` extends `HistorianExtensionPoint<HistorianSettings>`
- Extension point is registered via `GatewayModuleHook.getExtensionPoints()`
- Historian type appears in the "Create New Historian Profile" dropdown
- `AbstractHistorian` implementation with `QueryEngine` and `StorageEngine`

❌ **Not Working:**
- Configuration UI in the historian creation wizard
- Error message: "Web UI Component type not found"
- Cannot complete historian creation through the Gateway UI

## Questions

### 1. UI Component Registration for Third-Party Historians

**Question:** How do third-party modules provide a configuration UI for historian settings in Ignition 8.3+?

**Details:**
- Our `FactryHistorianSettings` class implements `HistorianSettings` with proper JavaBean properties
- We've created `.properties` files for field labels and descriptions
- When clicking "Next" in the historian creation wizard, we get: "Web UI Component type not found"
- We cannot find any public API method in `HistorianExtensionPoint` or `AbstractExtensionPoint` for registering UI components

**Specific questions:**
- Is there a method we need to override to provide a settings UI component?
- Should we implement a React component? If so, how do we register it?
- Are third-party modules expected to provide configuration UI through a different mechanism?
- Is this functionality planned for future SDK releases?

### 2. Extension Point API Completeness

**Question:** Is the `HistorianExtensionPoint` API intended to be fully extensible for third-party modules?

**Details:**
- We can create historian implementations successfully
- Extension points appear in the Gateway UI dropdown
- But we cannot complete the configuration workflow due to missing UI component

**Specific questions:**
- Is the current API limitation intentional (third-party historians not fully supported)?
- What is the recommended approach for third-party historian configuration?
- Should we use a different pattern (e.g., custom config panels instead of extension points)?

### 3. Recommended Architecture for Third-Party Historians

**Question:** What is the recommended architecture for creating a production-ready third-party historian module in Ignition 8.3+?

**Current approaches we're considering:**

**Option A: Extension Point with Programmatic Creation**
```java
// Extension point for discoverability
public class FactryHistorianExtensionPoint extends HistorianExtensionPoint<HistorianSettings> {
    // Registered via getExtensionPoints()
}

// Programmatic historian creation
@Override
public void startup(LicenseState activationState) {
    FactryHistorianSettings settings = loadSettingsFromConfig();
    historian = new FactryHistoryProvider(context, "FactryHistorian", settings);
    historian.startup();
}
```

**Option B: Custom Config Panel + Programmatic Creation**
```java
@Override
public Optional<List<ConfigPanel>> getConfigPanels() {
    return Optional.of(List.of(new FactryHistorianConfigPanel()));
}

// Create/manage historians based on config panel settings
```

**Option C: Wait for API Enhancement**
- Is UI component registration coming in a future release?
- Should we wait rather than implement a workaround?

**Specific questions:**
- Which approach is recommended by Inductive Automation?
- Are there examples of production third-party historian modules we can reference?
- What patterns do other third-party developers use?

### 4. Settings Management

**Question:** How should third-party historians handle settings persistence and management?

**Details:**
- Built-in historians use the Historian Core module's configuration system
- Third-party modules don't have access to this system

**Specific questions:**
- Should we use Ignition's `PersistentRecord` system for settings?
- Should we create our own database tables?
- Is there a standard pattern for historian settings management?
- How should settings be exposed to users for configuration?

### 5. Documentation and Examples

**Question:** Are there any documentation, examples, or reference implementations for third-party historian development?

**What we've reviewed:**
- SDK JavaDocs for Ignition 8.3
- Module development guide (general)
- Decompiled historian-gateway artifacts (to understand built-in implementations)

**What would be helpful:**
- Complete example of a third-party historian module
- Documentation specific to historian extension point usage
- Best practices guide for custom historians
- Migration guide from pre-8.3 historian APIs

## Module Details

- **Ignition Version:** 8.3.1
- **SDK Version:** 8.1.20
- **Module Structure:** Multi-scope (Gateway, Client, Designer, Common)
- **Historian Type:** External REST API integration (read/write)
- **Implementation:** AbstractHistorian with QueryEngine and StorageEngine

## Additional Context

Our use case requires:
1. **Storage:** Forward tag changes to external system via REST API
2. **Query:** Retrieve historical data from external system for PowerChart, Tag History bindings, etc.
3. **Configuration:** User-configurable endpoint URL, timeout, batch settings
4. **Discovery:** Appear in historian dropdown for easy tag configuration

We've successfully implemented #1, #2, and #4. The challenge is #3 (configuration UI).

## Forum Post Template

```
Title: Third-Party Historian Module - Configuration UI in Ignition 8.3

Hello,

I'm developing a custom historian module for Ignition 8.3 that integrates with an external
historian system. I've successfully implemented the historian using HistorianExtensionPoint,
and it appears in the "Create New Historian Profile" dropdown. However, when clicking "Next"
to configure the historian, I get the error: "Web UI Component type not found".

[Include relevant questions from above]

Any guidance would be greatly appreciated!

Module details:
- Ignition 8.3.1
- SDK 8.1.20
- HistorianExtensionPoint registered via getExtensionPoints()
- FactryHistorianSettings implements HistorianSettings

Thank you!
```

## References

- [Ignition 8.3 SDK JavaDocs](https://docs.inductiveautomation.com/docs/8.3/appendix/javadocs)
- [Module Development Guide](https://docs.inductiveautomation.com/docs/8.3/platform/modules/module-development)
- Forum post that helped us: "Extension points are registered by returning them from GatewayModuleHook::getExtensionPoints" (Kevin Herron)

## Related Documentation

See also:
- `docs/feasibility_study_findings.md` - Initial API research
- `docs/ignition_8.3_historian_api_research.md` - Detailed API analysis
- `docs/programmatic_historian_workaround.md` - Current workaround approach
