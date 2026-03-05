# Try-Out Guide

Step-by-step guide to get the Factry Historian module running for the first time.

## 1. Start Ignition

```bash
docker-compose up -d ignition
```

This starts **Ignition** at http://localhost:8088 (admin / password).

## 2. Start the proxy

In a separate terminal, build and run the Go proxy directly:

```bash
cd proxy
go build -o factry-proxy .
./factry-proxy
```

You should see:

```
Factry Historian Proxy Server
HTTP server on port :8111
gRPC server on port :9876
```

Keep this terminal open — gRPC messages from Ignition will appear here.

## 3. Build and install the module

```bash
./gradlew clean build
cp build/Factry-Historian.modl ignition/data/modules/
docker-compose restart ignition
```

On first install, go to **Config > System > Modules** and accept the **Mustry Solution** certificate.

## 4. Create a Historian

1. Go to **Config > Tags > History > Historians**
2. Click **Create New Historian Profile**
3. Select **Factry Historian**, click **Next**
4. Fill in the fields:

### Connection

| Field | Value | Notes |
|-------|-------|-------|
| Collector UUID | `<your-collector-uuid>` | UUID of the collector registered in Factry Historian |
| Token | `<your-bearer-token>` | Bearer token for Factry Historian authentication |
| Host | `host.docker.internal` | See "Host value" below |
| Port | `9876` | gRPC port of the Factry Historian server |

### Advanced

| Field | Value | Notes |
|-------|-------|-------|
| Batch Size | `100` | |
| Batch Interval (ms) | `5000` | |
| Debug Logging | checked | Recommended for first-time testing |

**Host value** depends on your setup:
- **Factry running on your host machine**: use `host.docker.internal` (Docker resolves this to the host)
- **Factry running remotely**: use the server hostname/IP
- **Ignition running natively** (not in Docker): use `localhost`

5. Click **Create Historian**

## 5. Create a test tag with history

1. Go to **Config > Tags > Tag Browser**
2. Create a new **Memory Tag** (e.g., `TestTag`, type: Integer)
3. Edit the tag, go to the **History** section
4. Set **History Enabled** = true
5. Set **History Provider** = the historian you created in step 4
6. Set **Sample Mode** = On Change (or All Data)
7. Save the tag

## 6. Trigger a write

Change the tag value manually in the Tag Browser. Each value change should trigger a write via gRPC.

In the proxy terminal you should see output like:

```
[gRPC] Received StoreRequest with 1 samples
[gRPC]   sample[0]: tag=prov:default:/tag:TestTag  time=2026-02-24 14:30:05.123  value_int=42  value_double=42.000000  quality=192
```

Check the Ignition gateway logs for messages like:

```
doStoreAtomic called with 1 points
gRPC store succeeded: stored, count=1
```

## Troubleshooting

### "Connection refused" or "UNAVAILABLE" in gateway logs
The gRPC host should be `host.docker.internal` when the proxy runs on the host machine, not `localhost`. Ignition runs inside Docker and `localhost` refers to the container itself.

### Tag history not appearing
- Check that the historian status shows **Running** in the Historians list
- Check that the tag's History Provider matches the historian name
- Check gateway logs for errors (search for "Factry" or "gRPC")

### Programmatic historian conflicts with UI-created one
The gateway hook auto-creates a "FactryHistorian" historian on startup (hardcoded to `factry-proxy:9876`). If you also create one from the UI, you will have two historians. The UI-created one uses the settings you provide, so use that one for testing with the local proxy.

### Fields appear duplicated in the Create Historian form
Rebuild and reinstall the module — this was a bug that has been fixed.
