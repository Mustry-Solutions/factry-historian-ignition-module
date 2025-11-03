# Module Update Problem

## Issue Description

When updating the `FactryHistorianExtensionPoint.java` code, the changes are compiled correctly but the runtime continues to execute old code.

## Timeline

1. **Initial Change**: Modified `FactryHistorianExtensionPoint.java` to pass literal strings instead of resource bundle keys:
   - Changed from: `super(TYPE_ID, "HistorianType.Name", "HistorianType.Description")`
   - Changed to: `super(TYPE_ID, "Factry Historian", "External historian...")`
   - Updated log statement from: `"type={}, nameKey={}, descriptionKey={}"`
   - Updated to: `"type={}, name={}, description={}"`

2. **Compilation Verified**: Decompiled bytecode from `build/Factry-Historian.modl` shows correct strings:
   ```
   ldc #5  // String Factry Historian
   ldc #7  // String External historian for Factry Historian system via REST API
   ldc #19 // String Factry Historian Extension Point created: type={}, name={}, description={}
   ```

3. **Runtime Behavior**: Gateway logs continue to show OLD log message:
   ```
   I [i.f.h.g.FactryHistorianExtensionPoint] [08:26:45.047]: Factry Historian Extension Point created: type=factry-historian, nameKey=HistorianType.Name, descriptionKey=HistorianType.Description
   ```

## Investigation Steps Taken

1. ✅ Verified source code has correct changes
2. ✅ Verified compiled bytecode has correct strings
3. ✅ Verified no "nameKey" or "descriptionKey" strings exist in source code anymore (grep search)
4. ✅ Removed jar-cache: `/usr/local/bin/ignition/data/jar-cache/io.factry.historian.FactryHistorian`
5. ✅ Found multiple module copies in container:
   - `/usr/local/bin/ignition/user-lib/modules/Factry-Historian.modl` (22K, Nov 3 08:22) ← Volume mount
   - `/usr/local/bin/ignition/data/Factry-Historian.modl` (22K, Nov 3 08:10)
   - `/usr/local/bin/ignition/data/var/ignition/modl/Factry-Historian.modl` (18K, Oct 30) ← OLD
   - `/usr/local/bin/ignition/data/modules/Factry-Historian.modl` (22K, Nov 3 08:17)
6. ✅ Wiped data directory with `docker compose down -v` and `rm -rf ignition/data/jar-cache ignition/data/var`
7. ❌ Still shows old log message after fresh restart

## Paradox

- **Bytecode inspection** of the built module shows NEW code
- **Runtime execution** shows OLD code
- **Source code search** finds no trace of old strings
- **Module file** timestamps show recent updates

## Possible Explanations

1. **Classloader caching**: Ignition may have multiple classloader layers, and the extension point is loaded very early (during setup phase) from a cached location
2. **Module persistence**: The `/usr/local/bin/ignition/data/var/ignition/modl/` directory may be where Ignition stores "installed" modules separately from mounted modules
3. **Volume mount timing**: Docker volume mounts might not be available during early Ignition initialization
4. **Module installation**: The module might need to be "installed" through the Gateway UI rather than just mounted

## UI Behavior

Despite the log message issue, checking the Gateway UI at:
**Config → Services → Historians → Create New Historian Profile**

The UI still shows:
```
¿HistorianType.Name?
¿HistorianType.Description?
```

This suggests the UI might be reading from resource bundle properties (which don't exist) rather than the constructor parameters.

## Recommended Next Steps

1. Try installing the module through Gateway UI instead of volume mounting
2. Check if Ignition loads extension points before volume mounts are available
3. Investigate if extension points require a different registration mechanism
4. Consider that the `¿?¿` markers might be unrelated to the log message issue
5. Ask Inductive Automation forum about:
   - Proper way to update mounted modules in Docker
   - Extension point localization requirements
   - Whether extension points support literal strings vs. resource bundle keys

## Resolution Status

**UNRESOLVED** - Paused investigation to focus on more important tasks.

The core functionality (historian creation, startup, registration) works correctly. This issue only affects:
- The log message during extension point creation
- The display name/description in the Gateway UI dropdown

Neither of these issues blocks the proof-of-concept testing.
