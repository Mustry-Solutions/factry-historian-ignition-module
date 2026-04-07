# Try-Out Guide

Step-by-step guide to get the Factry Historian module running with a real Factry Historian instance.

## 1. Start the services

```bash
docker-compose up -d
```

This starts:
- **Ignition** at http://localhost:8089 (admin / password)
- **Factry Historian** at http://localhost:8000 (factry / password)
- **Grafana** at http://localhost:3000 (admin / admin)

## 2. Set up Factry Historian

1. Open http://localhost:8000 and log in (factry / password)
2. Complete the **Setup Wizard**:

   **Step 1 — Organization**: Enter an organization name

   **Step 2 — Internal Time Series Database (InfluxDB)**:
   | Field | Value |
   |-------|-------|
   | Database type | Influx |
   | Admin user | `factry` |
   | Admin password | `password` |
   | Host | `http://influx:8086` |
   | Database name | `_internal_factry` |

   > Use the Docker service name `influx`, not `localhost` or `127.0.0.1`.

   **Step 3 — General Historian Settings**:
   | Field | Value | Notes |
   |-------|-------|-------|
   | GRPC port | `8001` | |
   | REST port | `8000` | |
   | URL | `http://historian` | Docker service name, used by collectors |
   | Base URL | `http://localhost:8000` | Browser access URL |

   > The **URL** field must use the Docker service name `historian`, not `localhost`, since collectors communicate within the Docker network.

## 3. Create a Time Series Database

1. Go to **Configuration > Time Series Databases**
2. Click **Create Database**
3. Fill in the fields:

   | Field | Value |
   |-------|-------|
   | Database type | Influx |
   | Admin user | `factry` |
   | Admin password | `password` |
   | Host | `http://influx:8086` |
   | Database name | e.g. `historian` |
   | Create database | enabled |

4. Save the database — note the **Database ID** for the next step

## 4. Create a Collector

1. Go to **Collectors** in the sidebar
2. Click **Create Collector**
3. Select the time series database created in step 3
4. Give it a name (e.g., `ignition-collector`)
5. Click **Generate Token** and **copy the token** — you'll need it in step 6
6. Note the **Collector ID** shown on the collector detail page

## 5. Download and install the module

1. Go to the **Releases** page of the GitHub repository
2. Download the latest `Factry-Historian.modl` file
3. Copy the module into the Ignition modules directory and restart:

```bash
cp Factry-Historian.modl ignition/data/modules/
docker-compose restart ignition
```

4. On first install, go to **Config > System > Modules** and accept the **Mustry Solution** certificate.

## 6. Create a Historian in Ignition

1. Go to **Config > Tags > History > Historians**
2. Click **Create New Historian Profile**
3. Select **Factry Historian**, click **Next**
4. Fill in the fields:

### Connection

| Field | Value | Notes |
|-------|-------|-------|
| Collector ID | `<uuid from step 4>` | The UUID shown on the collector page |
| Token | `<token from step 4>` | The generated bearer token |
| Host | `historian` | Docker service name (both containers share the same network) |
| Port | `8001` | Factry Historian gRPC port |

### Advanced

| Field | Value | Notes |
|-------|-------|-------|
| Batch Size | `100` | |
| Batch Interval (ms) | `5000` | |
| Debug Logging | checked | Recommended for first-time testing |

5. Click **Create Historian**

## 7. Create a test tag with history

1. Go to **Config > Tags > Tag Browser**
2. Create a new **Memory Tag** (e.g., `TestTag`, type: Integer)
3. Edit the tag, go to the **History** section
4. Set **History Enabled** = true
5. Set **History Provider** = the historian you created in step 6
6. Set **Sample Mode** = On Change (or All Data)
7. Save the tag

## 8. Trigger writes and verify in Factry

1. In Ignition's Tag Browser, change the tag value manually a few times
2. Open the Factry Historian web UI at http://localhost:8000
3. Go to **Measurements** — you should see a measurement matching your tag name
4. Click on the measurement to view its data points with the values you entered

You can also check the Ignition gateway logs for messages like:

```
gRPC store succeeded: stored, count=1
```

## Troubleshooting

### "UNAVAILABLE" or TLS errors in gateway logs
- Verify the Host is set to `historian` (the Docker service name), not `localhost`
- Verify the Port is `8001` (gRPC port), not `8000` (web UI port)

### Historian status shows error
- Check that the Collector ID and Token are correct
- Check the Factry Historian logs: `docker-compose logs historian`

### Tag history not appearing in Factry
- Check that the historian status shows **Running** in the Historians list
- Check that the tag's History Provider matches the historian name
- Check Ignition gateway logs for errors: `docker-compose logs ignition | grep -i factry`
