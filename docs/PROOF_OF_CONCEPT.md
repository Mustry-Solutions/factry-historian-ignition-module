# Factry Historian - Proof of Concept Guide

## Status: Ready for Testing

The Factry Historian module is now deployed and running in Ignition. The historian is created programmatically on module startup and is ready to be used with tags.

## Module Information

- **Historian Name:** `FactryHistorian`
- **Proxy URL:** `http://localhost:8111`
- **Status:** Running
- **Debug Logging:** Enabled

## Proof of Concept Goals

### Goal 1: Query Historical Data (Provider)
**Objective:** Add Factry Historian to a tag and view historical data in PowerChart

**Test Flow:**
```
Tag with History → FactryHistorian → PowerChart Display
                         ↓
                  GET /provider
                         ↓
                Golang Proxy Server
                         ↓
                 Return Mock Data
```

### Goal 2: Collect Tag Changes (Collector)
**Objective:** Change a memo tag value and verify the collector sends data to the proxy

**Test Flow:**
```
Memo Tag Value Change → FactryHistorian → POST /collector
                              ↓
                     Golang Proxy Server
                              ↓
                      Print to Console
```

## Setup Instructions

### Step 1: Access Ignition Gateway

1. Open browser to: http://localhost:8088
2. Login credentials: `admin` / `password`
3. Navigate to: **Config → Tags → default**

### Step 2: Create a Test Tag for Provider (Query)

1. Click **New Tag** → **Memory Tag**
2. Configure:
   - **Name:** `TestQueryTag`
   - **Data Type:** `Float8` (Double)
   - **Value:** `100.0`
3. Go to **Tag → History** section
4. Configure History:
   - **History Enabled:** `☑ Enabled`
   - **Storage Provider:** Select **`FactryHistorian`** from dropdown
   - **Sample Mode:** `On Change`
   - **Max Time Between Samples:** `10 seconds`
5. Click **Save**

### Step 3: Create a Test Tag for Collector (Storage)

1. Click **New Tag** → **Memory Tag**
2. Configure:
   - **Name:** `TestCollectorTag`
   - **Data Type:** `Float8` (Double)
   - **Value:** `50.0`
3. Go to **Tag → History** section
4. Configure History:
   - **History Enabled:** `☑ Enabled`
   - **Storage Provider:** Select **`FactryHistorian`** from dropdown
   - **Sample Mode:** `On Change`
   - **Max Time Between Samples:** `10 seconds`
5. Click **Save**

### Step 4: Verify Historian is Running

Check the Gateway logs to confirm the historian started:

```bash
docker compose logs ignition | grep "Factry Historian started successfully"
```

Expected output:
```
Factry Historian started successfully!
Historian Name: FactryHistorian
Proxy URL: http://localhost:8111
```

### Step 5: Start the Golang Proxy Server

Make sure your Golang proxy server is running on `http://localhost:8111` with:
- `/collector` endpoint - receives tag value changes
- `/provider` endpoint - returns historical data

The proxy should accept POST requests with JSON payloads.

### Step 6: Test the Collector (Storage)

1. In Designer or Gateway, change the `TestCollectorTag` value:
   - Set value to `75.0`
   - Wait a moment
   - Set value to `125.0`

2. Check Ignition logs for storage activity:
```bash
docker compose logs ignition -f | grep -i "store\|collector"
```

Expected logs:
```
Would store 1 atomic points to http://localhost:8111/collector
```

3. Check your Golang proxy console for incoming POST requests to `/collector`

### Step 7: Test the Provider (Query)

1. Create a Perspective or Vision view
2. Add a **Power Chart** component
3. Configure the Power Chart:
   - Add pen: `[default]TestQueryTag`
   - Time range: Last 1 hour
   - Enable historical data

4. Check Ignition logs for query activity:
```bash
docker compose logs ignition -f | grep -i "query\|provider"
```

Expected logs:
```
Would query tag paths from http://localhost:8111/provider
```

5. Check your Golang proxy console for incoming POST requests to `/provider`

## Expected Golang Proxy Requests

### Collector Request (POST /collector)

```json
{
  "samples": [
    {
      "tagPath": "[default]TestCollectorTag",
      "timestamp": 1698765432000,
      "value": 75.0,
      "quality": 192
    }
  ]
}
```

### Provider Request (POST /provider)

```json
{
  "tagPaths": ["[default]TestQueryTag"],
  "startTime": 1698765432000,
  "endTime": 1698769032000,
  "maxPoints": 1000
}
```

### Expected Provider Response

```json
{
  "success": true,
  "message": "OK",
  "data": {
    "[default]TestQueryTag": [
      {
        "tagPath": "[default]TestQueryTag",
        "timestamp": 1698765432000,
        "value": 100.0,
        "quality": 192
      },
      {
        "tagPath": "[default]TestQueryTag",
        "timestamp": 1698765492000,
        "value": 105.5,
        "quality": 192
      }
    ]
  }
}
```

## Verification Checklist

- [ ] Module loads successfully in Ignition
- [ ] `FactryHistorian` appears in History Provider dropdown
- [ ] Tags can be configured with FactryHistorian as storage provider
- [ ] Changing tag values triggers storage engine
- [ ] PowerChart queries trigger query engine
- [ ] HTTP requests reach Golang proxy `/collector` endpoint
- [ ] HTTP requests reach Golang proxy `/provider` endpoint
- [ ] PowerChart displays data returned from proxy

## Troubleshooting

### Historian Not Available in Dropdown

Check if the historian started:
```bash
docker compose logs ignition | grep "Factry Historian"
```

### No HTTP Requests to Proxy

1. Verify proxy is running on `http://localhost:8111`
2. Check Ignition logs for errors:
```bash
docker compose logs ignition -f | grep -i "error\|exception"
```
3. Verify debug logging is enabled (should see "Would store..." messages)

### Connection Refused

If you see connection errors:
1. Ensure proxy server is running
2. Check proxy is accessible from Docker container:
```bash
docker compose exec ignition curl http://host.docker.internal:8111
```

Note: If using `localhost:8111` from within the container, it refers to the container's localhost, not your host machine. You may need to use `host.docker.internal:8111` instead.

To update the URL, modify `FactryHistorianGatewayHook.java`:
```java
settings.setUrl("http://host.docker.internal:8111");
```

### Tags Not Recording History

1. Verify History is enabled on the tag
2. Check Storage Provider is set to `FactryHistorian`
3. Try changing the tag value to trigger a sample
4. Check for errors in gateway logs

## Debug Logging

The module has debug logging enabled by default. To see detailed logs:

```bash
# Follow all Factry Historian logs
docker compose logs ignition -f | grep "i.f.h.g"

# See storage attempts
docker compose logs ignition -f | grep "storage\|atomic points"

# See query attempts
docker compose logs ignition -f | grep "query\|tag paths"
```

## Next Steps After POC

Once the proof of concept is validated:

1. **Implement Real HTTP Communication**
   - Replace "Would store..." log messages with actual HTTP POST to `/collector`
   - Replace "Would query..." log messages with actual HTTP POST to `/provider`
   - Parse and process responses

2. **Error Handling**
   - Handle HTTP timeouts
   - Retry logic for failed requests
   - Graceful degradation when proxy is unavailable

3. **Configuration UI**
   - Ask Inductive Automation about UI component registration (see `docs/Questions.md`)
   - Implement custom config panel for settings management
   - Allow runtime configuration changes

4. **Production Readiness**
   - Batch multiple tag changes before sending to collector
   - Implement connection pooling for HTTP requests
   - Add metrics and monitoring
   - Load testing with high-volume tag changes

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Ignition Gateway                         │
│                                                              │
│  ┌────────────────────────────────────────────────────┐    │
│  │         Factry Historian Module                     │    │
│  │                                                      │    │
│  │  ┌──────────────────────────────────────────────┐  │    │
│  │  │  FactryHistoryProvider                        │  │    │
│  │  │  (AbstractHistorian)                          │  │    │
│  │  │                                               │  │    │
│  │  │  ├─ FactryQueryEngine                        │  │    │
│  │  │  │  (Reads historical data)                  │  │    │
│  │  │  │                                            │  │    │
│  │  │  └─ FactryStorageEngine                      │  │    │
│  │  │     (Writes tag changes)                     │  │    │
│  │  │                                               │  │    │
│  │  │  └─ FactryHttpClient                         │  │    │
│  │  │     (HTTP communication)                     │  │    │
│  │  └──────────────────────────────────────────────┘  │    │
│  └────────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────────┘
                           │
                           │ HTTP
                           ↓
              ┌──────────────────────────┐
              │   Golang Proxy Server    │
              │   localhost:8111         │
              │                          │
              │  POST /collector         │
              │  POST /provider          │
              └──────────────────────────┘
                           │
                           ↓
              ┌──────────────────────────┐
              │  Factry Historian System │
              └──────────────────────────┘
```

## Success Criteria

The proof of concept is **successful** when:

✅ Tags can use `FactryHistorian` as their storage provider
✅ Changing tag values triggers storage engine with visible logs
✅ PowerChart queries trigger query engine with visible logs
✅ HTTP requests reach the Golang proxy server (visible in proxy logs)
✅ No errors or exceptions in Ignition logs
✅ Data flow is bidirectional (storage and retrieval)

## Current Implementation Status

✅ Module compiles and loads in Ignition 8.3.1
✅ Historian created programmatically on startup
✅ Historian registered with name `FactryHistorian`
✅ Available in tag History Provider dropdown
✅ QueryEngine and StorageEngine implemented
✅ HTTP client infrastructure ready
✅ Debug logging enabled

⏳ Pending: Actual HTTP implementation (currently logs "Would store..." / "Would query...")
⏳ Pending: Response parsing and data transformation
⏳ Pending: Golang proxy server implementation
