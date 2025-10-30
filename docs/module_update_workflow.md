# Module Update Workflow

This document describes the **verified working procedure** for updating the Factry Historian module during development.

## Issue: Browser Upload Fails

**Problem**: Uploading the module via the Ignition Gateway web interface results in errors.

**Solution**: Use Docker Compose volume mounting for module updates.

## Working Workflow (Verified October 30, 2025)

### 1. Build the Module

```bash
# Ensure you're using Java 11
java11

# Clean build with signing
./gradlew clean build
```

This produces:
- **Signed module**: `build/Factry-Historian.modl` (use this for production)
- **Unsigned module**: `build/Factry-Historian.unsigned.modl`

### 2. Copy Module to Docker Volume

The Docker Compose setup mounts the `ignition/` folder. Copy the **signed** module directly to the data directory:

```bash
# Copy signed module
cp build/Factry-Historian.modl ignition/data/

# Remove any old unsigned versions (if present)
rm ignition/data/Factry-Historian.unsigned.modl 2>/dev/null || true
```

**Important**: Copy to `ignition/data/` NOT `ignition/data/modules/` - Ignition will detect modules in the data directory.

### 3. Restart Ignition Gateway

```bash
# Using docker compose (without hyphen)
docker compose restart ignition
```

### 4. View Logs to Verify Loading

```bash
# Follow logs in real-time
docker compose logs ignition -f --tail=100

# Or search for specific module logs
docker compose logs ignition --tail=200 | grep -i "factry"
```

Look for log messages like:
- `Factry Historian Module - Setup Starting`
- `Creating Factry History Provider...`
- `Factry Historian Module - Setup Complete`

### 5. Accept Module Certificate (First Time Only)

After the first installation or when the module certificate changes:

1. Navigate to **Config → System → Modules** in the Ignition Gateway (http://localhost:8088)
2. Find the **Factry Historian** module
3. The module status will show a certificate acceptance prompt
4. Accept the certificate from **Mustry Solution**
5. The module status should change to **ACTIVE** (or **FAULTED** if there are runtime errors)

## Quick Reference Commands

```bash
# Full workflow in one command
java11 && ./gradlew clean build && cp build/Factry-Historian.modl ignition/data/ && docker compose restart ignition

# Watch logs after restart
docker compose logs ignition -f --tail=100

# Check module status in logs
docker compose logs ignition --tail=300 | grep -A 10 "Factry Historian"
```

## Verification Checklist

After each update:

1. ✅ Check **Config → System → Modules** in Gateway (http://localhost:8088)
2. ✅ Verify module appears in the list
3. ✅ Check module status (ACTIVE/FAULTED)
4. ✅ Review Gateway logs for setup/startup messages
5. ✅ Verify log messages show correct behavior

## Module Status Meanings

- **ACTIVE**: Module loaded successfully and is running
- **FAULTED**: Module loaded but encountered errors (check logs for details)
- **LOADED**: Module present but not started
- Certificate prompt: Module needs certificate acceptance

## Notes

- Browser upload via Gateway web interface is **not working** currently
- Docker volume mounting is the **verified working** development workflow
- Use **signed** module (`build/Factry-Historian.modl`) for better compatibility
- Certificate acceptance is required on first install or certificate changes
- Module ID: `io.factry.historian.FactryHistorian`
- Gateway URL: http://localhost:8088 (credentials: admin / password)

## Troubleshooting

### Module Not Loading
- Check Gateway logs: `docker compose logs ignition --tail=200`
- Verify module file is in `ignition/data/` (NOT `ignition/data/modules/`)
- Ensure no file permission issues (should be readable by container)
- Check file timestamp to ensure latest version was copied: `ls -lh ignition/data/Factry-Historian.modl`

### Module Status is FAULTED
- Check logs for exceptions: `docker compose logs ignition --tail=300 | grep -A 30 "Exception"`
- Common causes:
  - Missing module dependencies (check `NoClassDefFoundError` in logs)
  - Runtime errors in hook lifecycle methods (setup, startup)
  - Missing required libraries
- Review the full exception stack trace in logs

### Certificate Issues
- Navigate to **Config → System → Modules**
- Look for certificate acceptance prompts
- Accept the **Mustry Solution** certificate
- Restart gateway if needed

### Changes Not Reflecting
- Verify you copied the **newly built** module (check timestamp with `ls -lh build/`)
- Ensure you **restarted** the Gateway after copying: `docker compose restart ignition`
- Check that old module file was overwritten
- Clear browser cache if viewing in web UI

### Logs Not Showing Module Messages
- Confirm module loaded: `docker compose logs ignition | grep -i "factry"`
- Check for exceptions during module load
- Verify module is in correct location: `ls -lh ignition/data/Factry-Historian.modl`
- Ensure Docker volume is mounted correctly

## Current Known Issues (October 30, 2025)

### FAULTED Status - Missing Historian Dependency
**Error**: `NoClassDefFoundError: com/inductiveautomation/historian/gateway/api/AbstractHistorian`

**Cause**: Module does not declare dependency on `com.inductiveautomation.historian` (Historian Core)

**Solution**: Add module dependency in `build.gradle.kts`:
```kotlin
moduleDependencySpecs {
    register("com.inductiveautomation.historian") {
        scope = "G"
        required = true
    }
}
```
