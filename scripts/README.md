# Factry Historian Proxy

Mock historian proxy server with collector and provider endpoints for testing the Factry Historian Ignition module.

## Overview

This Go application provides two HTTP endpoints that simulate an external historian system:

- **`/collector`**: Receives tag data from Ignition (batch writes) and logs to console
- **`/provider`**: Returns historical data to Ignition (with randomly generated values)

The data structures follow Ignition's standard historian format with OPC quality codes.

## Quick Start

### Installation

```bash
cd proxy

# Download dependencies
go mod download

# Install Swagger CLI tool (needed to generate docs)
go install github.com/swaggo/swag/cmd/swag@latest
```

### Generate Swagger Documentation

Before running the server for the first time, generate the Swagger docs:

```bash
swag init
```

This will create a `docs/` folder with the OpenAPI specification.

### Running the Server

```bash
go run .
```

The server will start on **port 8111** with the following endpoints:
- `http://localhost:8111/collector` (POST)
- `http://localhost:8111/provider` (POST)
- `http://localhost:8111/health` (GET)
- `http://localhost:8111/swagger/index.html` (GET) - Swagger UI

## Swagger UI

Once the server is running, open your browser to:

**http://localhost:8111/swagger/index.html**

The Swagger UI provides:
- **Interactive API documentation** - See all endpoints and data models
- **Try it out** - Test endpoints directly from the browser
- **Request/Response examples** - See sample JSON payloads
- **Schema definitions** - View data structure specifications

## API Documentation

### 1. Collector Endpoint

**POST** `/collector`

Receives batch tag data writes from Ignition. Prints all received samples to console and discards them.

#### Request Body

```json
{
  "samples": [
    {
      "tagPath": "[default]Temperature",
      "timestamp": 1704067200000,
      "value": 72.5,
      "quality": 192
    },
    {
      "tagPath": "[default]Pressure",
      "timestamp": 1704067200000,
      "value": 101.3,
      "quality": 192
    }
  ]
}
```

#### Response

```json
{
  "success": true,
  "message": "Samples received and logged",
  "count": 2
}
```

#### Field Descriptions

- `tagPath` (string): Full tag path from Ignition (e.g., `[default]TagName`)
- `timestamp` (int64): Unix timestamp in milliseconds
- `value` (any): Tag value - can be number, string, boolean, etc.
- `quality` (int): OPC quality code
  - `192` = Good (0xC0)
  - `0` = Bad
  - `64` = Uncertain (0x40)

#### Example cURL Request

```bash
curl -X POST http://localhost:8111/collector \
  -H "Content-Type: application/json" \
  -d '{
    "samples": [
      {
        "tagPath": "[default]Temperature",
        "timestamp": 1704067200000,
        "value": 72.5,
        "quality": 192
      }
    ]
  }'
```

### 2. Provider Endpoint

**POST** `/provider`

Queries historical data for specified tags. Returns randomly generated data points for testing.

#### Request Body

```json
{
  "tagPaths": ["[default]Temperature", "[default]Pressure"],
  "startTime": 1704067200000,
  "endTime": 1704070800000,
  "maxPoints": 100
}
```

#### Response

```json
{
  "success": true,
  "data": {
    "[default]Temperature": [
      {
        "tagPath": "[default]Temperature",
        "timestamp": 1704067200000,
        "value": 68.42,
        "quality": 192
      },
      {
        "tagPath": "[default]Temperature",
        "timestamp": 1704067236000,
        "value": 71.89,
        "quality": 192
      }
    ],
    "[default]Pressure": [
      {
        "tagPath": "[default]Pressure",
        "timestamp": 1704067200000,
        "value": 100.15,
        "quality": 192
      }
    ]
  }
}
```

#### Field Descriptions

- `tagPaths` ([]string): List of tag paths to query
- `startTime` (int64): Query start time in Unix milliseconds
- `endTime` (int64): Query end time in Unix milliseconds
- `maxPoints` (int, optional): Maximum data points per tag (default: 100)

#### Example cURL Request

```bash
curl -X POST http://localhost:8111/provider \
  -H "Content-Type: application/json" \
  -d '{
    "tagPaths": ["[default]Temperature"],
    "startTime": 1704067200000,
    "endTime": 1704070800000,
    "maxPoints": 50
  }'
```

### 3. Health Check

**GET** `/health`

Simple health check endpoint.

```bash
curl http://localhost:8111/health
# Response: OK
```

## Data Structures

### OPC Quality Codes

The proxy uses standard OPC UA quality codes compatible with Ignition:

| Code | Constant          | Description                    |
|------|-------------------|--------------------------------|
| 192  | `QualityGood`     | Good quality (0xC0)           |
| 0    | `QualityBad`      | Bad quality                    |
| 64   | `QualityUncertain`| Uncertain quality (0x40)      |

### Random Data Generation

The provider endpoint generates random data with the following characteristics:

- **Value Types**: Mix of floats (0-100), integers (0-1000), and booleans
- **Quality Distribution**:
  - 90% Good quality (192)
  - 5% Bad quality (0)
  - 5% Uncertain quality (64)
- **Timestamps**: Evenly distributed between start and end time based on maxPoints

## Console Output Example

When the collector receives data, it logs to console:

```
[COLLECTOR] Received 2 samples
  [1] TagPath: [default]Temperature, Time: 2024-01-01T00:00:00Z, Value: 72.5, Quality: Good (192)
  [2] TagPath: [default]Pressure, Time: 2024-01-01T00:00:00Z, Value: 101.3, Quality: Good (192)
```

When the provider generates data:

```
[PROVIDER] Query for 2 tags from 2024-01-01T00:00:00Z to 2024-01-01T01:00:00Z (max 100 points)
[PROVIDER]   Generating data for tag: [default]Temperature
[PROVIDER]   Generating data for tag: [default]Pressure
```

## Testing Examples

### Test Collector Endpoint

Send a batch of tag samples:

```bash
curl -X POST http://localhost:8111/collector \
  -H "Content-Type: application/json" \
  -d '{
    "samples": [
      {
        "tagPath": "[default]TestTag1",
        "timestamp": 1704067200000,
        "value": 42.5,
        "quality": 192
      },
      {
        "tagPath": "[default]TestTag2",
        "timestamp": 1704067200000,
        "value": true,
        "quality": 192
      },
      {
        "tagPath": "[default]TestTag3",
        "timestamp": 1704067260000,
        "value": 100,
        "quality": 64
      }
    ]
  }'
```

### Test Provider Endpoint

Query historical data for multiple tags:

```bash
curl -X POST http://localhost:8111/provider \
  -H "Content-Type: application/json" \
  -d '{
    "tagPaths": ["[default]TestTag1", "[default]TestTag2"],
    "startTime": 1704067200000,
    "endTime": 1704070800000,
    "maxPoints": 20
  }'
```

### Test with Different Time Ranges

Query last hour of data (use current timestamp):

```bash
# Get current timestamp in milliseconds (on macOS/Linux)
NOW=$(date +%s)000
START=$((NOW - 3600000))

curl -X POST http://localhost:8111/provider \
  -H "Content-Type: application/json" \
  -d "{
    \"tagPaths\": [\"[default]Temperature\"],
    \"startTime\": $START,
    \"endTime\": $NOW,
    \"maxPoints\": 60
  }"
```

### Test with Pretty Print

Add `| jq` to format JSON output:

```bash
curl -X POST http://localhost:8111/collector \
  -H "Content-Type: application/json" \
  -d '{
    "samples": [
      {"tagPath": "[default]Test", "timestamp": 1704067200000, "value": 123, "quality": 192}
    ]
  }' | jq
```

## Building for Production

```bash
# Regenerate Swagger docs (if API changed)
swag init

# Build binary
go build -o factry-proxy

# Run binary
./factry-proxy
```

**Note**: Make sure to run `swag init` before building if you've made changes to API annotations.

## Configuration

To change the port, edit `main.go`:

```go
const (
    Port = ":8111"  // Change to desired port
)
```

## Troubleshooting

### Swagger UI not loading

If you get errors about missing docs package:

```bash
# Make sure you've generated the docs
swag init

# Check that docs/ folder was created
ls docs/

# Try running again
go run .
```

### "swag: command not found"

Install the Swagger CLI tool:

```bash
go install github.com/swaggo/swag/cmd/swag@latest

# Make sure $GOPATH/bin is in your PATH
export PATH=$PATH:$(go env GOPATH)/bin
```

## Integration with Ignition Module

This proxy is designed to be called from the Factry Historian Ignition module:

1. **Collector**: Module calls `/collector` endpoint when tag values change
2. **Provider**: Module calls `/provider` endpoint when Ignition requests historical data

The data structures are compatible with Ignition's historian system and OPC quality codes.

## Future Enhancements

For production use, consider:
- Persistent storage (database) instead of discarding data
- Authentication/authorization
- Rate limiting
- Compression for large datasets
- Aggregation modes (avg, min, max, count)
- Data validation and error handling
- Metrics and monitoring
