# Web UI Implementation for Historian Configuration

## Goal
Enable users to configure the Factry Historian through the Gateway UI instead of getting "Web UI Component type not found" error.

## Implementation (Version 0.1.1)

### 1. Created `FactryHistorianConfig.java`
A Java record with nested records for configuration categories:
- **Connection** category:
  - `url` (String): Proxy REST API URL
  - `timeoutMs` (int): HTTP timeout in milliseconds
- **Advanced** category:
  - `batchSize` (int): Batch size for data collection
  - `batchIntervalMs` (int): Batch interval in milliseconds
  - `debugLogging` (boolean): Enable debug logging

Uses annotations from `com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations`:
- `@FormCategory` - Groups fields into sections
- `@Label` - Display label for the field
- `@FormField` - Specifies the HTML form field type
- `@DefaultValue` - Default value for the field
- `@Required` - Marks field as required
- `@Description` - Tooltip/help text

### 2. Implemented `getWebUiComponent()` in `FactryHistorianExtensionPoint.java`

```java
@Override
public Optional<WebUiComponent> getWebUiComponent(ComponentType type) {
    return Optional.of(
        new ExtensionPointResourceForm(
            resourceType(),  // Historian resource type from parent class
            "Factry Historian Configuration",  // Form title
            TYPE_ID,  // Extension point type ID
            null,  // No profile config (unlike devices)
            SchemaUtil.fromType(FactryHistorianConfig.class),  // Our config schema
            Set.of()  // No additional capabilities
        )
    );
}
```

### 3. Key Classes Used
- `ExtensionPointResourceForm` - The web form component
- `SchemaUtil.fromType()` - Converts annotated record to JSON schema for web UI
- `WebUiComponent` - Return type for web UI components
- `ComponentType` - The type of component being requested (likely FORM or CONFIG)

## Testing

### Version Tracking
- **Current Version**: 0.1.1 (updated from 0.1.0)
- Version is set in `build.gradle.kts` line 17
- Visible in Gateway UI at: Config → System → Modules

### Test Steps
1. Navigate to Gateway UI: `http://localhost:8088`
2. Go to: Config → Tags → History (or Config → Services → Historians)
3. Click "Create new Historian profile..."
4. Select "Factry Historian" from dropdown
5. Click "Next"
6. **Expected**: Configuration form with Connection and Advanced sections
7. **Actual (as of 0.1.1)**: Still showing "Web UI Component type not found"

## Potential Issues

### 1. Profile Config Parameter
The device example uses:
```java
SchemaUtil.fromType(DeviceProfileConfig.class),  // Profile-level config
SchemaUtil.fromType(ExampleDeviceConfig.class),  // Instance-level config
```

We're using:
```java
null,  // No profile config
SchemaUtil.fromType(FactryHistorianConfig.class),  // Instance config
```

**Question**: Do historians require a profile-level config class? Or should both parameters be the same?

### 2. Resource Type
We're using `resourceType()` from the parent class. This should return the correct historian resource type, but we haven't verified what it actually returns.

**Possible fix**: Define our own resource type constant?

### 3. ComponentType Handling
The `getWebUiComponent(ComponentType type)` receives a type parameter. We're ignoring it and always returning the form.

**Question**: Do we need to check the type and return different components based on it?

### 4. Method Not Being Called
Add logging to verify `getWebUiComponent()` is actually being called when clicking "Next" in the UI.

**Current log**: Line 68 logs `"getWebUiComponent called with type: {}"` but we haven't seen this in logs yet.

### 5. Missing Dependencies
The web UI classes might require additional runtime dependencies that aren't included in the module.

**Possible fix**: Check if we need to add explicit dependencies for web navigation classes.

## Next Steps

1. ✅ Version bumped to 0.1.1 for tracking
2. ⏳ User testing to verify version appears as 0.1.1
3. ⏳ Check if `getWebUiComponent()` log appears when clicking "Next"
4. ❓ Try different parameter combinations for `ExtensionPointResourceForm`:
   - Pass config for both parameters instead of null
   - Try using `SchemaUtil.fromType(HistorianSettings.class)` instead
5. ❓ Ask on Inductive Automation forum about:
   - Correct schema parameters for historian extension points
   - Whether profile config is required
   - Examples of third-party historian UI implementation

## References

- Device example: `ignition-sdk-examples/opc-ua-device/.../ExampleDeviceExtensionPoint.java`
- Config example: `ignition-sdk-examples/opc-ua-device/.../ExampleDeviceConfig.java`
- Forum help from IA: Suggests using `ExtensionPointResourceForm` with `SchemaUtil.fromType()`
