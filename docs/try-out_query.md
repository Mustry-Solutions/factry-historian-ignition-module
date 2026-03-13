# Try-Out Guide: Query Side (with Fake gRPC Server)

This guide walks you through testing the **query/provider side** of the Factry Historian module using the fake gRPC server. You'll be able to browse measurements and plot dummy historical data in Power Chart — no real Factry Historian instance needed.

## Prerequisites

- Docker Desktop running
- Go installed (`go version`)
- Java 17 (`java -version`)
- Module already built at least once (`./gradlew clean build`)

## 1. Start the Fake gRPC Server

The fake server listens on port `9876` (plain TCP, no TLS) and comes pre-populated with 3 measurements:

| Name | Data Type | Dummy Pattern |
|------|-----------|---------------|
| `prov:default:/tag:Sine_Wave` | number | Sine wave (period = 1h, amplitude = 50, offset = 50) |
| `prov:default:/tag:Pump_Running` | boolean | Alternating true/false every minute |
| `prov:default:/tag:Temperature` | number | Sine wave (same pattern) |

Start it:

```bash
cd scripts
go run grpc_server.go
```

You should see:

```
Fake Factry Historian gRPC server listening on :9876
Pre-populated 3 measurements
```

Leave this terminal open — you'll see all gRPC calls logged here.

## 2. Verify with Test Scripts (Optional)

In a **new terminal**, verify the server works. Use `config.fake.json` (pre-configured for the fake server on port 9876) and `--plaintext` (the fake server doesn't use TLS):

```bash
cd scripts

# List pre-populated measurements
go run ./query_measurements/ --config config.fake.json --plaintext

# Output:
# UUID                                      NAME                                      STATUS    DATATYPE
# pre-0001-0001-0001-000000000001           prov:default:/tag:Sine_Wave               active    number
# pre-0002-0002-0002-000000000002           prov:default:/tag:Pump_Running             active    boolean
# pre-0003-0003-0003-000000000003           prov:default:/tag:Temperature              active    number
```

Query points for a measurement:

```bash
# Query last hour of sine wave data (default: 100 points)
go run ./query_points/ --config config.fake.json --plaintext --uuid pre-0001-0001-0001-000000000001

# Query with custom time range and limit
go run ./query_points/ --config config.fake.json --plaintext --uuid pre-0001-0001-0001-000000000001 --limit 10
```

You can also create additional measurements:

```bash
go run ./create_measurement/ --config config.fake.json --plaintext --name "prov:default:/tag:MyCustomTag" --type number
go run ./query_measurements/ --config config.fake.json --plaintext   # verify it appears
```

> **Flags explained**:
> - `--config config.fake.json` — uses the fake server config (port 9876) instead of the default `config.json` (port 8001 for real Factry Historian)
> - `--plaintext` — disables TLS (the fake server runs plain TCP, while the real Factry Historian uses TLS)

## 3. Start Ignition

```bash
docker-compose up -d ignition
```

Wait for it to be healthy:

```bash
docker-compose ps
# ignition-dev should show "healthy"
```

Gateway: http://localhost:8088 (admin / password)

## 4. Build and Install the Module

```bash
# Build
./gradlew clean build

# Deploy (stop → replace → start)
docker compose stop ignition && \
  rm -f ignition/data/Factry-Historian.modl && \
  cp build/Factry-Historian.modl ignition/data/ && \
  docker compose start ignition
```

On **first install**: go to **Config > System > Modules** and accept the **Mustry Solution** certificate.

Verify the module is **ACTIVE** in the modules list.

## 5. Create a Factry Historian Profile

The fake server runs on your **host machine** (not in Docker), so Ignition (inside Docker) needs to reach it via the Docker host IP.

1. Go to **Config > Tags > History > Historians**
2. Click **Create New Historian Profile**
3. Select **Factry Historian**, click **Next**
4. Fill in:

### Connection

| Field | Value | Notes |
|-------|-------|-------|
| Collector ID | `test-collector-001` | Any string, the fake server doesn't validate |
| Token | `fake-token` | Any string, the fake server doesn't validate |
| Host | `host.docker.internal` | Special Docker DNS that resolves to your host machine |
| Port | `9876` | The fake server port |

### Advanced

| Field | Value |
|-------|-------|
| Debug Logging | checked |

5. Click **Create Historian**

> **Important**: The host must be `host.docker.internal`, not `localhost`. Ignition runs inside Docker and `localhost` would refer to the container itself.

Check the Ignition logs to verify it connects:

```bash
docker compose logs ignition --tail=50 | grep -i factry
```

You should see:

```
Factry Historian - Starting Up
gRPC client created (TLS), target=host.docker.internal:9876
Measurement cache pre-populated with 3 entries
```

And in the fake server terminal:

```
[gRPC] GetMeasurements: collectorUUID=test-collector-001
```

## 6. Create a Perspective Project with Power Chart

### 6a. Open the Designer

1. Download the Ignition Designer Launcher from http://localhost:8088
2. Launch it and connect to `localhost:8088`
3. Log in with admin / password

### 6b. Create a New Project

1. In the Designer, click **New Project**
2. Name it (e.g., `QueryTest`)
3. Make sure **Perspective** is enabled
4. Click **Create**

### 6c. Add a Power Chart

1. In the Perspective project, open the default **Page** (or create a new view)
2. In the **Component Palette** on the left, search for **Power Chart** (under Charts)
3. Drag it onto the view and resize it to fill most of the space

### 6d. Configure the Power Chart

The Power Chart in Perspective uses tag history bindings. You can add pens in two ways:

**Option A — Add pens at runtime** (easiest for testing):

1. Open the project in a browser: http://localhost:8088/data/perspective/client/QueryTest
2. In the Power Chart toolbar, click the **tag browse** icon
3. Expand your Factry Historian provider
4. You should see the pre-populated measurements:
   - `Sine_Wave`
   - `Pump_Running`
   - `Temperature`
5. Select one or more tags and confirm

**Option B — Configure pens in the Designer**:

1. Select the Power Chart component
2. In the **Property Editor**, expand `props.pens`
3. Add a new pen and configure:
   - **name**: `Sine_Wave`
   - **source**: set the tag path pointing to your Factry Historian
4. Repeat for additional measurements

### 6e. Configure the Time Range

The fake server generates data at 1-minute intervals within whatever time range you request. By default, Power Chart shows the last 8 hours, which gives ~480 data points per measurement.

## 7. View the Data in a Browser

1. Open the Perspective session in your browser: http://localhost:8088/data/perspective/client/QueryTest
2. The Power Chart should load and show the tag browser
3. Browse into the Factry Historian → select a measurement
4. The chart should display historical data

### What You Should See

- **Sine_Wave / Temperature**: A smooth sine wave oscillating between 0 and 100, with a 1-hour period
- **Pump_Running**: A square wave alternating between 0 (false) and 1 (true) every minute

### Adjusting the View

- Use the **time range selector** at the bottom to zoom in/out
- The default 8-hour range gives a nice view of multiple sine wave cycles
- Zoom into a 10-minute window to clearly see the 1-minute data resolution

## 8. What Happens Behind the Scenes

When you add a tag and the chart loads:

```
Power Chart                    Ignition                     FactryQueryEngine              Fake gRPC Server
    │                              │                              │                              │
    ├─ "browse historian" ────────►│                              │                              │
    │                              ├─ doBrowse() ────────────────►│                              │
    │                              │                              ├─ GetMeasurements ───────────►│
    │                              │                              │◄── 3 measurements ───────────┤
    │                              │◄─ [Sine_Wave, Pump, Temp] ──┤                              │
    │◄─ show tag list ────────────┤                              │                              │
    │                              │                              │                              │
    ├─ "show Sine_Wave data" ─────►│                              │                              │
    │                              ├─ doQueryRaw(keys, range) ───►│                              │
    │                              │                              ├─ QueryRawPoints ────────────►│
    │                              │                              │   (uuid, T1, T2)             │
    │                              │                              │◄── sine wave points ─────────┤
    │                              │                              │                              │
    │                              │                              ├─ processor.onInitialize()    │
    │                              │                              ├─ processor.onPointAvailable() (×N)
    │                              │                              ├─ processor.onComplete()      │
    │                              │◄─ DataSet with points ──────┤                              │
    │◄─ render chart ─────────────┤                              │                              │
```

## Troubleshooting

### No measurements show up in the tag browser

- Check that the fake gRPC server is still running
- Check Ignition logs for TLS/connection errors:
  ```bash
  docker compose logs ignition --tail=100 | grep -i "factry\|grpc\|error"
  ```
- The fake server doesn't use TLS but the gRPC client does. If you see TLS handshake errors, this is expected with the current setup — the client creates a TLS channel. You may need to temporarily modify `FactryGrpcClient` to use plaintext for local testing, or run the fake server behind a TLS proxy.

### Connection refused errors

- Verify the host is `host.docker.internal` (not `localhost`)
- Verify the fake server is running on port `9876`
- Test connectivity from inside the container:
  ```bash
  docker compose exec ignition curl -v telnet://host.docker.internal:9876
  ```

### TLS errors connecting to the fake server

The `FactryGrpcClient` always creates a TLS-enabled channel, but the fake server runs plain TCP. Two options:

**Option A**: Use the real Factry Historian instead (see `docs/try-out.md`)

**Option B**: Temporarily add a plaintext gRPC constructor to `FactryGrpcClient` for local development. This is the most common issue when testing with the fake server.

### Power Chart shows "No Data"

- Check the time range — make sure it overlaps with a period where data would be generated
- Check that the historian profile status is **Running** (not faulted)
- Look at the fake server terminal — you should see `QueryRawPoints` log entries when the chart requests data
- Check Ignition logs for query errors

### Historian status is FAULTED

- Check the Ignition logs for the startup exception
- Common cause: connection refused (host/port misconfigured)
- Verify module dependencies: the module requires the Historian Core module to be loaded first

## Cleanup

When done testing:

1. Stop the fake server: `Ctrl+C` in the terminal
2. Optionally remove the historian profile from Ignition
3. Stop Ignition: `docker-compose down`
