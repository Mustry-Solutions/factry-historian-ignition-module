# Module Deployment and Update Process

## Issue: Module Updates Not Working with Docker Volume Mount

### Problem
When using Docker volume mounts to deploy the module (`docker-compose.yml` with volume mount to `user-lib/modules/`), Ignition **caches** the module and does not reload it even after:
- Rebuilding the module
- Restarting the Docker container
- Clearing jar-cache directories
- Complete Docker `down -v` and restart

**Symptom**: Gateway UI continues to show old version (e.g., 0.1.0) even though the `.modl` file has been rebuilt with a new version (e.g., 0.1.3).

### Root Cause
Ignition maintains an internal module registry and cache system at `/usr/local/bin/ignition/data/var/ignition/modl/` that persists across container restarts. Volume-mounted modules may be loaded once and then cached indefinitely.

## Solution: Manual Module Installation via Gateway UI

### ✅ Working Method

**Always use manual uninstall → reinstall through the Gateway UI when updating modules:**

1. **Build the new module**:
   ```bash
   # Update version in build.gradle.kts (e.g., 0.1.3 → 0.1.4)
   # Update version in FactryHistorianModule.MODULE_VERSION
   ./gradlew clean build
   ```

2. **Uninstall the old module**:
   - Navigate to: Config → System → Modules
   - Find "Factry Historian"
   - Click the trash/delete icon
   - Confirm uninstallation
   - **Wait for module to be removed** (page will refresh)

3. **Restart Gateway** (optional but recommended):
   ```bash
   docker compose restart ignition
   ```
   Or use the Gateway web UI: Config → System → Gateway Control → Restart

4. **Install the new module**:
   - Config → System → Modules
   - Click "Install or Upgrade a Module"
   - Click "Choose File"
   - Select `build/Factry-Historian.modl` from your local machine
   - Click "Install"
   - Wait for installation and automatic Gateway restart

5. **Verify the version**:
   - Check Config → System → Modules
   - Version should show the new version (e.g., "0.1.4 (b0)")
   - Check Gateway logs for version confirmation:
     ```
     I [i.f.h.g.FactryHistorianExtensionPoint] [...]: MODULE VERSION: 0.1.4
     I [i.f.h.g.FactryHistorianGatewayHook] [...]: MODULE VERSION: 0.1.4
     ```

### ❌ Methods That Don't Work

1. **Docker volume mount hot-reload**:
   ```yaml
   # This DOES NOT update existing modules:
   volumes:
     - ./build/Factry-Historian.modl:/usr/local/bin/ignition/user-lib/modules/Factry-Historian.modl
   ```
   Volume mounts work for **initial installation** but not for **updates**.

2. **Docker restart alone**:
   ```bash
   # This does NOT reload updated modules:
   docker compose restart ignition
   ```
   The old cached module continues to run.

3. **Copying to data directory**:
   ```bash
   # This does NOT trigger module reload:
   cp build/Factry-Historian.modl ignition/data/modules/
   ```
   Ignition doesn't detect file changes in mounted directories.

4. **Clearing jar-cache**:
   ```bash
   # This does NOT help:
   docker compose exec ignition rm -rf /usr/local/bin/ignition/data/jar-cache/io.factry.historian.FactryHistorian
   ```
   Ignition loads from internal registry, not just jar-cache.

## Version Tracking

### Code-Based Version Display
To easily verify which version is running, we added version logging in multiple places:

#### 1. Version Constant
**File**: `common/src/main/java/io/factry/historian/common/FactryHistorianModule.java`
```java
public static final String MODULE_VERSION = "0.1.3";
```
**Important**: Update this constant whenever you bump version in `build.gradle.kts`!

#### 2. Extension Point Constructor
**File**: `gateway/src/main/java/io/factry/historian/gateway/FactryHistorianExtensionPoint.java`
```java
public FactryHistorianExtensionPoint() {
    super(TYPE_ID, DISPLAY_NAME, DESCRIPTION);
    logger.info("========================================");
    logger.info("Factry Historian Extension Point created");
    logger.info("MODULE VERSION: {}", io.factry.historian.common.FactryHistorianModule.MODULE_VERSION);
    logger.info("Type: {}, Name: {}", TYPE_ID, DISPLAY_NAME);
    logger.info("========================================");
}
```

#### 3. Setup Phase
**File**: `gateway/src/main/java/io/factry/historian/gateway/FactryHistorianGatewayHook.java`
```java
@Override
public void setup(GatewayContext context) {
    logger.info("========================================");
    logger.info("Factry Historian Module - Setup Starting");
    logger.info("MODULE VERSION: {}", io.factry.historian.common.FactryHistorianModule.MODULE_VERSION);
    logger.info("========================================");
    // ...
}
```

#### 4. Startup Phase
**File**: `gateway/src/main/java/io/factry/historian/gateway/FactryHistorianGatewayHook.java`
```java
@Override
public void startup(LicenseState activationState) {
    logger.info("========================================");
    logger.info("Factry Historian Module - Startup");
    logger.info("MODULE VERSION: {}", io.factry.historian.common.FactryHistorianModule.MODULE_VERSION);
    logger.info("License State: {}", activationState.toString());
    logger.info("========================================");
    // ...
}
```

### Checking the Version

**In Gateway UI**:
- Config → System → Modules
- Look for "Factry Historian" row
- Version column shows: `X.Y.Z (b0)`

**In Gateway Logs**:
```bash
docker compose logs ignition | grep "MODULE VERSION"
```
Expected output:
```
I [i.f.h.g.FactryHistorianExtensionPoint] [...]: MODULE VERSION: 0.1.3
I [i.f.h.g.FactryHistorianGatewayHook] [...]: MODULE VERSION: 0.1.3  (setup)
I [i.f.h.g.FactryHistorianGatewayHook] [...]: MODULE VERSION: 0.1.3  (startup)
```

If logs show a different version than the Gateway UI, the old cached module is still running.

## Version Update Checklist

When bumping the module version:

- [ ] Update `build.gradle.kts` line 17: `version = "0.1.X"`
- [ ] Update `FactryHistorianModule.java` line 10: `MODULE_VERSION = "0.1.X"`
- [ ] Run: `./gradlew clean build`
- [ ] **Uninstall** old module via Gateway UI
- [ ] **Restart** Gateway (optional)
- [ ] **Install** new module via Gateway UI (upload `build/Factry-Historian.modl`)
- [ ] Verify version in Gateway UI matches
- [ ] Verify version in logs matches: `docker compose logs ignition | grep "MODULE VERSION"`

## Development Workflow

### For Initial Installation
Use Docker volume mount for convenience:
```yaml
# docker-compose.yml
volumes:
  - ./build/Factry-Historian.modl:/usr/local/bin/ignition/user-lib/modules/Factry-Historian.modl
```

### For Module Updates
Always use manual UI-based uninstall/reinstall cycle.

### For Production
Use the Gateway backup/restore or module installation API (if available).

## Related Documentation

- Module update caching issue: `docs/module_update_problem.md`
- Web UI implementation: `docs/web_ui_implementation.md`
