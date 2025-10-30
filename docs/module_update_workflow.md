# Module Update Workflow

This document describes the working procedure for updating the Factry Historian module during development.

## Issue: Browser Upload Fails

**Problem**: Uploading the module via the Ignition Gateway web interface results in errors.

**Solution**: Use Docker Compose volume mounting for automatic module updates.

## Working Workflow

### 1. Build the Module

```bash
# Ensure you're using Java 11
java11

# Build the module (without signing for development)
./gradlew clean build
```

This produces: `build/Factry-Historian.unsigned.modl`

### 2. Copy Module to Docker Volume

The Docker Compose setup mounts the `ignition/` folder. Copy the built module to the modules directory:

```bash
cp build/Factry-Historian.unsigned.modl ignition/data/modules/
```

### 3. Restart Ignition Gateway

```bash
docker-compose restart ignition
```

### 4. Accept Module Certificate (First Time Only)

After the first installation or when the module certificate changes:

1. Navigate to **Config → System → Modules** in the Ignition Gateway
2. Find the **Factry Historian** module
3. The module status will show a certificate acceptance prompt
4. Accept the certificate from **Mustry Solution**
5. The module status should change to **ACTIVE**

## Alternative: Manual Module Install via Docker CLI

If the volume mount method doesn't work:

```bash
# Copy module into running container
docker cp build/Factry-Historian.unsigned.modl ignition:/usr/local/bin/ignition/user-lib/modules/

# Restart the container
docker-compose restart ignition
```

## Verification

After each update:

1. Check **Config → System → Modules** in Gateway
2. Verify module status is **ACTIVE**
3. Check the module version number matches your build
4. Review Gateway logs for any errors:
   ```bash
   docker-compose logs -f ignition
   ```

## Notes

- Browser upload via Gateway web interface is **not working** currently
- Docker volume mounting is the **recommended** development workflow
- Module must be **unsigned** for development (`skipModlSigning.set(true)`)
- Certificate acceptance is required on first install or certificate changes
- Module ID: `io.factry.historian.FactryHistorian`

## Troubleshooting

### Module Not Loading
- Check Gateway logs: `docker-compose logs ignition`
- Verify module file is in `ignition/data/modules/`
- Ensure no file permission issues (should be readable by container)

### Certificate Issues
- Navigate to **Config → System → Modules**
- Look for certificate acceptance prompts
- Accept the **Mustry Solution** certificate

### Changes Not Reflecting
- Verify you copied the **newly built** module (check timestamp)
- Ensure you **restarted** the Gateway after copying
- Check that old module file was overwritten
