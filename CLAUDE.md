# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the **Factry Historian Module** for Inductive Automation's Ignition platform. It's an Ignition SDK module that implements a custom historian with two main components:
- **Collector (Storage Provider)**: Writes tag data to external storage via REST API
- **Provider (History Provider)**: Reads historical data from external storage and provides it to Ignition

The module integrates with Ignition's Tag History system and supports standard historian features like deadbands, sample modes, and DataSet organization.

## Ignition Version Targeting

**IMPORTANT**: This module targets **Ignition 8.3+** exclusively. Do not maintain backward compatibility with earlier versions.

Key implications:
- **Use 8.3+ APIs**: The module system changed significantly in Ignition 8.3. Always reference 8.3+ documentation and examples.
- **Module Dependencies**: Use the newer `moduleDependencySpecs` block (not `moduleDependencies`) to leverage the "required" flag feature introduced in 8.3
- **SDK Version**: Currently using SDK 8.1.20, but APIs and patterns should follow 8.3+ conventions
- **Deprecated APIs**: Avoid any APIs or patterns marked as deprecated or designed for pre-8.3 compatibility

When searching for documentation or examples, always specify "Ignition 8.3" or later to avoid outdated patterns from the 8.1/8.2 era.

## Java Version Requirement

**CRITICAL**: This project requires **Java 11** to build and run.

The codebase is configured with Java 11 toolchain in all `build.gradle.kts` files. To switch to Java 11:
```bash
# Use the alias (after sourcing ~/.zshrc)
java11

# Or set JAVA_HOME manually for the build
export JAVA_HOME=$(/usr/libexec/java_home -v 11)
```

Java 21/22 will cause build failures, particularly with Gradle and Kotlin toolchains.

## Build Commands

### Basic Build
```bash
# Clean build (requires Java 11)
./gradlew clean build

# Build without signing (for development)
# Edit build.gradle.kts and set: skipModlSigning.set(true)
./gradlew build
```

### Module Signing
The module requires signing certificates configured in `gradle.properties`:
```properties
ignition.signing.keystoreFile=certificates/keystore.jks
ignition.signing.keystorePassword=<password>
ignition.signing.certAlias=<alias>
ignition.signing.certFile=certificates/Mustry_Modules_Root_CA.p7b
ignition.signing.certPassword=<password>
```

**IMPORTANT**: Never commit `gradle.properties` with real passwords to git.

For development, set `skipModlSigning.set(true)` in `build.gradle.kts` (line 120).

### Build Output
- Signed module: `build/Factry-Historian.modl`
- Unsigned module: `build/Factry-Historian.unsigned.modl`

## Project Architecture

### Multi-Scope Module Structure

The module uses Ignition's standard multi-scope architecture with 4 subprojects:

1. **`:common`** (Scope: GCD - Gateway, Client, Designer)
   - Shared code across all scopes
   - Contains module constants like `MODULE_ID`
   - No Ignition API dependencies beyond `ignition-common`

2. **`:gateway`** (Scope: G - Gateway only)
   - Server-side historian logic
   - Hook: `FactryHistorianGatewayHook`
   - Implements Storage Provider and History Provider interfaces
   - Handles REST API communication with external Factry system

3. **`:client`** (Scope: CD - Client and Designer)
   - Vision Client runtime code
   - Hook: `FactryHistorianClientHook`

4. **`:designer`** (Scope: D - Designer only)
   - Designer-specific functionality
   - Hook: `FactryHistorianDesignerHook`

### Module Configuration

Module metadata is defined in root `build.gradle.kts`:
- **Module ID**: `io.factry.historian.FactryHistorian`
- **Display Name**: Factry Historian
- **Ignition SDK Version**: 8.1.20
- **Target Ignition Version**: 8.3+ (not backward compatible with 8.1/8.2)
- **Required Ignition Version**: Currently set to 8.1.20 but module uses 8.3+ features

Scope mappings in `projectScopes`:
- Gateway: "G"
- Client: "CD"
- Designer: "D"
- Common: "GCD"

**Note**: While `requiredIgnitionVersion` is set to SDK version 8.1.20, this module is designed exclusively for Ignition 8.3+ and uses APIs/features that may not work on earlier versions.

### Hook Classes

Each scope has a hook class that extends Ignition's base hook:
- Gateway: `AbstractGatewayModuleHook` - implements lifecycle methods (setup, startup, shutdown)
- Client: Extends appropriate client hook base class
- Designer: Extends appropriate designer hook base class

The gateway hook is where most module logic lives, including:
- Config panels (`getConfigPanels()`)
- Web routes (`mountRouteHandlers()`)
- Resource mounting (`getMountedResourceFolder()`)
- Licensing (`isFreeModule()`)

## Development Workflow

### Adding Gateway Functionality

1. Implement logic in `gateway/src/main/java/io/factry/historian/gateway/`
2. Add Ignition SDK dependencies in `gateway/build.gradle.kts`:
   ```kotlin
   dependencies {
       compileOnly("com.inductiveautomation.ignitionsdk:gateway-api:${rootProject.extra["sdk_version"]}")
       compileOnly(project(":common"))
   }
   ```
3. Override relevant hook methods in `FactryHistorianGatewayHook`

### Adding Module Dependencies

**Use `moduleDependencySpecs` (Ignition 8.3+)** to declare module dependencies with the "required" flag:

```kotlin
moduleDependencySpecs {
    register("com.inductiveautomation.opcua") {
        scope = "G"           // Gateway scope only
        required = true       // Module won't load without this dependency
    }
    register("com.inductiveautomation.vision") {
        scope = "CD"          // Client and Designer scopes
        required = false      // Optional dependency
    }
}
```

**Do NOT use the legacy `moduleDependencies` map** - it's for Ignition 8.1/8.2 compatibility only:
```kotlin
// ❌ DON'T USE - Legacy pattern for 8.1/8.2
moduleDependencies.set(mapOf("G" to "com.inductiveautomation.opcua"))
```

The `moduleDependencySpecs` block allows marking dependencies as required, which causes the gateway to fault on module load if dependencies are missing (8.3+ feature).

### Gradle Plugin

This project uses the `io.ia.sdk.modl` Gradle plugin (v0.4.0) which:
- Packages all scopes into a single `.modl` file
- Signs the module with provided certificates
- Generates the `module.xml` descriptor
- Assembles dependencies into `build/moduleContent/`

## Repository URLs

The project uses Inductive Automation's Maven repository:
- **URL**: `https://nexus.inductiveautomation.com/repository/public`
- Configured in both `settings.gradle` (dependencies) and plugin management

## Documentation

Development documentation: `docs/content.md`
Project plan: `docs/plan.md`

### Ignition SDK Resources (8.3+)
When implementing features, always reference Ignition 8.3+ documentation:
- **SDK JavaDocs**: https://docs.inductiveautomation.com/docs/8.3/appendix/javadocs
- **Module Development Guide**: Focus on 8.3+ sections
- **SDK Examples**: Look for examples specifically marked as 8.3 compatible
- **Forum Posts**: Filter for 8.3+ discussions when searching for solutions

## Common Issues

### Build fails with "cannot access class com.sun.tools.javac.main.JavaCompiler"
- **Cause**: Building with Java 16+ instead of Java 11
- **Solution**: Switch to Java 11 using `java11` alias or setting `JAVA_HOME`

### "Required certificate file location not found"
- **Cause**: Missing or incorrectly configured `gradle.properties`
- **Solution**: Either configure certificates in `gradle.properties` or set `skipModlSigning.set(true)` in `build.gradle.kts`

### Module doesn't appear in Ignition after installation
- Check that hook classes are correctly mapped in the `hooks` block
- Verify module ID matches between `build.gradle.kts` and `FactryHistorianModule.MODULE_ID`
- Check Ignition gateway logs for loading errors
