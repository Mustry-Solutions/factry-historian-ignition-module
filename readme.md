# Factry Historian Module

An Ignition 8.3+ module that implements a custom historian backed by [Factry Historian](https://factry.io). Tag history data is stored and queried via a Go REST proxy that bridges Ignition with the Factry Historian API.

## Build

Requires **Java 17**.

```bash
# Without signing (development)
./gradlew clean build          # set skipModlSigning.set(true) in build.gradle.kts

# With signing
./gradlew clean build
```

Output: `build/Factry-Historian.modl` (signed) or `build/Factry-Historian.unsigned.modl`

## Certificates

Module signing uses Mustry certificates. Place them in a `certificates/` directory (git-ignored):

```
certificates/
  keystore.jks
  Mustry_Modules_Root_CA.p7b
  Mustry_Modules_Root_CA.pfx
```

Configure in `gradle.properties` (also git-ignored):

```properties
ignition.signing.keystoreFile=certificates/keystore.jks
ignition.signing.keystorePassword=<password>
ignition.signing.certAlias=<alias>
ignition.signing.certFile=certificates/Mustry_Modules_Root_CA.p7b
ignition.signing.certPassword=<password>
```

To skip signing during development, set `skipModlSigning.set(true)` in `build.gradle.kts`.

## Install

1. Start the development environment:
   ```bash
   docker-compose up -d
   ```
2. Build the module:
   ```bash
   ./gradlew clean build
   ```
3. Copy the module into the Ignition data directory:
   ```bash
   cp build/Factry-Historian.unsigned.modl ignition/data/modules/
   ```
4. Restart Ignition:
   ```bash
   docker-compose restart ignition
   ```
5. Open the Gateway at http://localhost:8088 and accept the **Mustry Solution** certificate in **Config > System > Modules**.
