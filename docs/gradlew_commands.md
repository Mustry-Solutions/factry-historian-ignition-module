# Gradle Commands

Quick reference for all Gradle tasks used during development.

## Build

| Command | Description |
|---------|-------------|
| `./gradlew build` | Incremental build (compile + unit tests) |
| `./gradlew clean build` | Full clean build |
| `./gradlew clean` | Delete the build directory |

## Test

| Command | Description |
|---------|-------------|
| `./gradlew test` | Run unit tests |
| `./gradlew integrationTest` | Run integration tests (requires running Docker environment) |

## Deploy

| Command | Description |
|---------|-------------|
| `./gradlew copy` | Build and copy the module to the Ignition modules directory |
| `./gradlew restart` | Copy the module and restart the Ignition Docker container |

`restart` depends on `copy`, which depends on `build` — so `./gradlew restart` does the full build-copy-restart cycle.

## Other

| Command | Description |
|---------|-------------|
| `./gradlew printVersion` | Print the current project version |
| `./gradlew tasks` | List all available tasks |
| `./gradlew dependencies` | Show dependency tree |
