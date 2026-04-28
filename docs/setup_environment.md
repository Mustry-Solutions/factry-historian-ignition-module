# Development Environment Setup

Guide for setting up the local development environment, building the module from source, and running tests.

## Prerequisites

- **Java 17** — Required for building and running the module
- **Docker** and **Docker Compose** — For running Ignition, Factry Historian, and supporting services

### Verify Java Version

```bash
java -version  # Should show Java 17
```

If you have multiple Java versions installed, ensure Java 17 is active before building.

## 1. Start the Docker Services

```bash
docker-compose up -d
```

This starts:

| Service | URL | Credentials |
|---------|-----|-------------|
| Ignition Gateway | http://localhost:8089 | admin / password |
| Factry Historian | http://localhost:8000 | — |
| Grafana | http://localhost:3050 | admin / admin |
| PostgreSQL | localhost:5432 | factry / password |
| InfluxDB | localhost:8086 | factry / password |

## 2. Set Up Factry Historian

1. Open http://localhost:8000
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

   > Use the Docker service name `influx`, not `localhost`.

   **Step 3 — General Historian Settings**:

   | Field | Value | Notes |
   |-------|-------|-------|
   | GRPC port | `8001` | |
   | REST port | `8000` | |
   | URL | `http://historian` | Docker service name, used by collectors |
   | Base URL | `http://localhost:8000` | Browser access URL |

   > The **URL** field must use the Docker service name `historian`, not `localhost`, since collectors communicate within the Docker network.

3. Create a **Time Series Database**:
   - Go to **Configuration > Time Series Databases**
   - Click **Create Database**

   | Field | Value |
   |-------|-------|
   | Database type | Influx |
   | Admin user | `factry` |
   | Admin password | `password` |
   | Host | `http://influx:8086` |
   | Database name | e.g. `historian` |
   | Create database | enabled |

> **Note:** Without a Factry license, the setup wizard must be completed again after every container restart.

## 3. Build the Module

```bash
# Production build (with signing)
./gradlew clean build
```

Output:
- `build/Factry-Historian.modl` (signed)
- `build/Factry-Historian.unsigned.modl` (unsigned)

### Module Signing (Production)

Place certificates in a `certificates/` directory (git-ignored):

```
certificates/
  keystore.jks
  cert.p7b
```

Configure in `gradle.properties` (also git-ignored):

```properties
ignition.signing.keystoreFile=certificates/keystore.jks
ignition.signing.keystorePassword=<password>
ignition.signing.certAlias=factry-modules
ignition.signing.certFile=certificates/cert.p7b
ignition.signing.certPassword=<password>
```

## 4. Install the Module in the Dev Environment

```bash
./gradlew copy restart
```

This copies the module to the Ignition modules directory, and restarts the Ignition Docker container.

On first install, go to **Config > System > Modules** and accept the **Factry** certificate.

See [gradlew_commands.md](gradlew_commands.md) for all available commands.

## 5. Run Tests

### Unit Tests

```bash
./gradlew test
```

### Integration Tests

Integration tests require the full Docker environment running with a configured historian.

```bash
./gradlew integrationTest
```

The integration test configuration is in `gateway/build.gradle.kts`. Key settings are passed as system properties or environment variables:

| Property | Env Variable | Default |
|----------|-------------|---------|
| `gateway.url` | `GATEWAY_URL` | `http://localhost:8089` |
| `historian.name` | `HISTORIAN_NAME` | `Factry Historian 1.0` |
| `grpc.host` | `GRPC_HOST` | `localhost` |
| `grpc.port` | `GRPC_PORT` | `8001` |
| `collector.name` | `COLLECTOR_NAME` | `Ingition` |

The integration tests use WebDev endpoints deployed in a `TestFactry` project on the Ignition gateway.

## Troubleshooting

### "UNAVAILABLE" or TLS errors in gateway logs
- Verify the Host is set to `historian` (the Docker service name), not `localhost`
- Verify the Port is `8001` (gRPC port), not `8000` (web UI port)

### Historian status shows error
- Check that the Token is correct
- Check the Factry Historian logs: `docker-compose logs historian`

### Tag history not appearing in Factry
- Check that the historian status shows **Running** in the Historians list
- Check that the tag's History Provider matches the historian name
- Check Ignition gateway logs: `docker-compose logs ignition | grep -i factry`

### Build fails with Java errors
- Ensure Java 17 is active: `java -version`
- The build will fail with other Java versions
