# Docker Development Environment Setup

This guide explains how to use Docker Compose for local Ignition 8.3.1 development with the Factry Historian module.

## Prerequisites

- Docker Desktop installed and running
- Docker Compose v2+
- Module built: `./gradlew build`

## Quick Start

### 1. First Time Setup (Recommended)

**Use the automated setup script:**

```bash
# Run the setup script (first time only)
./setup-ignition.sh
```

This script will:
- Start Ignition without mounted volumes
- Wait for initialization
- Copy data to local `ignition/` folder
- Restart with mounted volumes
- Verify everything works

**After first setup**, use regular docker-compose commands:

```bash
# Start services
docker-compose up -d

# Stop services
docker-compose down

# View logs
docker-compose logs -f
```

### 1b. Manual Setup (Alternative)

If you prefer manual control:

```bash
# Start both Ignition and the Go proxy
docker-compose up -d

# View logs
docker-compose logs -f

# Or start just Ignition
docker-compose up -d ignition
```

**Note**: On first run, you may see initialization errors. This is normal - Ignition will eventually initialize properly.

### 2. Wait for Startup

Ignition takes about 30-60 seconds to start. Check status:

```bash
# Check if services are healthy
docker-compose ps

# Follow Ignition logs
docker-compose logs -f ignition
```

### 3. Access Ignition Gateway

Open your browser to:

**Gateway**: http://localhost:8088

**Default Credentials**:
- Username: `admin`
- Password: `password`

⚠️ **Change these** in `docker-compose.yml` before using in any shared environment!

### 4. Install the Module

Two options:

#### Option A: Manual Install (First Time)

1. Build the module: `./gradlew build`
2. Go to: http://localhost:8088 → Config → System → Modules
3. Click "Install or Upgrade a Module"
4. Upload: `build/Factry-Historian.modl`
5. Restart gateway when prompted

#### Option B: Auto-Install (After First Setup)

Uncomment this line in `docker-compose.yml`:

```yaml
# - ./build/Factry-Historian.modl:/usr/local/bin/ignition/user-lib/modules/Factry-Historian.modl
```

Then:
```bash
docker-compose down
docker-compose up -d
```

The module will be automatically installed on startup.

## Data Persistence

All Ignition data is stored in `./ignition/data/` including:

- **Modules**: `./ignition/data/modules/`
- **Projects**: `./ignition/data/projects/`
- **Gateway Config**: `./ignition/data/db/`
- **Logs**: `./ignition/data/logs/`

This folder is **git-ignored** and will persist across container restarts.

### Starting Fresh

To completely reset Ignition:

```bash
docker-compose down
rm -rf ./ignition/data
docker-compose up -d
```

## Testing the Factry Historian

### 1. Verify Proxy is Running

```bash
curl http://localhost:8111/health
# Should return: OK

# Open Swagger UI
open http://localhost:8111/swagger/index.html
```

### 2. Test Proxy Endpoints

**Collector (Write)**:
```bash
curl -X POST http://localhost:8111/collector \
  -H "Content-Type: application/json" \
  -d '{
    "samples": [
      {
        "tagPath": "[default]TestTag",
        "timestamp": 1704067200000,
        "value": 42.5,
        "quality": 192
      }
    ]
  }'
```

**Provider (Read)**:
```bash
curl -X POST http://localhost:8111/provider \
  -H "Content-Type: application/json" \
  -d '{
    "tagPaths": ["[default]TestTag"],
    "startTime": 1704067200000,
    "endTime": 1704070800000,
    "maxPoints": 10
  }'
```

### 3. Check Module Logs

Once the module is installed:

```bash
# View Ignition gateway logs
docker-compose exec ignition tail -f /usr/local/bin/ignition/data/logs/wrapper.log

# Or view logs directory
ls -la ./ignition/data/logs/
```

Look for:
```
INFO  [FactryHistorianGatewayHook] Factry Historian Module - Setup Starting
INFO  [FactryHistorianGatewayHook] Creating Factry History Provider...
INFO  [FactryHistoryProvider] Factry Historian created: name=FactryHistorian
```

## Common Tasks

### Rebuild and Test Module

```bash
# 1. Rebuild module
./gradlew clean build

# 2. Remove old module from Ignition
# Go to Gateway → Config → System → Modules → Remove Factry Historian

# 3. Upload new module
# Upload build/Factry-Historian.modl

# Or use auto-install (if configured):
docker-compose restart ignition
```

### View Container Logs

```bash
# All services
docker-compose logs -f

# Just Ignition
docker-compose logs -f ignition

# Just Proxy
docker-compose logs -f factry-proxy
```

### Stop/Start Services

```bash
# Stop all
docker-compose down

# Start all
docker-compose up -d

# Restart specific service
docker-compose restart ignition

# Stop but keep volumes
docker-compose stop
```

### Access Container Shell

```bash
# Ignition container
docker-compose exec ignition bash

# Check Ignition installation
docker-compose exec ignition ls -la /usr/local/bin/ignition/

# Proxy container
docker-compose exec factry-proxy sh
```

## Troubleshooting

### Ignition Won't Start

Check logs:
```bash
docker-compose logs ignition | grep -i error
```

Common issues:
- Port 8088 already in use: Change port in `docker-compose.yml`
- Insufficient memory: Increase `IGNITION_OPTS: "-Xmx2g"` to `-Xmx4g`
- Corrupted data: Remove `./ignition/data` and restart

### Module Installation Fails

1. Check module file exists: `ls -lh build/Factry-Historian.modl`
2. Check Ignition version compatibility in gateway
3. Check gateway logs: `./ignition/data/logs/wrapper.log`

### Proxy Not Accessible

```bash
# Check if proxy is running
docker-compose ps factry-proxy

# Check proxy logs
docker-compose logs factry-proxy

# Test from inside Ignition container
docker-compose exec ignition curl http://factry-proxy:8111/health
```

### Can't Connect from Ignition to Proxy

Use the Docker service name instead of localhost:

**From Ignition module code**: Use `http://factry-proxy:8111` (not `http://localhost:8111`)

Both containers are on the same Docker network (`factry-historian-dev`).

## Network Architecture

```
Host Machine (Your Computer)
├── Port 8088 → Ignition Gateway
├── Port 8043 → Ignition HTTPS
├── Port 8060 → Gateway Network
└── Port 8111 → Factry Proxy

Docker Network: factry-historian-dev
├── ignition-dev (container)
│   └── Can access: http://factry-proxy:8111
└── factry-proxy (container)
    └── Can access: http://ignition-dev:8088
```

## Configuration

### Change Ignition Version

Edit `docker-compose.yml`:
```yaml
image: inductiveautomation/ignition:8.3.1  # Change version here
```

Available versions: https://hub.docker.com/r/inductiveautomation/ignition/tags

### Change Gateway Admin Credentials

Edit `docker-compose.yml`:
```yaml
environment:
  GATEWAY_ADMIN_USERNAME: "your-username"
  GATEWAY_ADMIN_PASSWORD: "your-secure-password"
```

⚠️ **Note**: These only apply on first startup. To change existing credentials, use the Gateway web interface.

### Adjust Memory Settings

Edit `docker-compose.yml`:
```yaml
environment:
  IGNITION_OPTS: "-Xmx4g"  # Increase to 4GB
```

## Production Considerations

⚠️ **This setup is for LOCAL DEVELOPMENT ONLY**

Before using in production:

1. ✅ Use strong passwords
2. ✅ Enable HTTPS
3. ✅ Configure proper backups
4. ✅ Use environment variables for secrets (not hardcoded)
5. ✅ Add proper logging and monitoring
6. ✅ Use production-ready database (not embedded H2)
7. ✅ Configure proper gateway network security
8. ✅ Review Ignition security best practices

## Additional Resources

- **Ignition Docker Hub**: https://hub.docker.com/r/inductiveautomation/ignition
- **Ignition Documentation**: https://docs.inductiveautomation.com/docs/8.3/
- **Docker Compose Docs**: https://docs.docker.com/compose/
- **Module Development**: See `docs/ignition_historian_module_development.md`

## Quick Reference

```bash
# Start environment
docker-compose up -d

# Stop environment
docker-compose down

# Rebuild module and test
./gradlew build && docker-compose restart ignition

# View logs
docker-compose logs -f

# Reset everything
docker-compose down && rm -rf ./ignition/data && docker-compose up -d

# Access gateway
open http://localhost:8088
```
