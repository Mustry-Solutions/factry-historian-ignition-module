# Ignition 8.3 Historian API Research
**Date**: January 2025
**Ignition Version**: 8.3.0 (Released August 2024)
**SDK Version**: 8.3.1
**Java Version**: 17
**JavaDoc Source**: https://files.inductiveautomation.com/sdk/javadoc/ignition83/8.3.1/

---

## Overview

Ignition 8.3 introduced a new public Historian API that enables module developers to implement custom historians. This is a **brand new API** (released August 2025), and documentation is still incomplete. According to forum discussions, complete documentation may not be available for 6+ months.

---

## Core Historian API Packages

### Main API Package
- `com.inductiveautomation.historian.gateway.api` - Public API interfaces

### Sub-packages
- `com.inductiveautomation.historian.gateway.api.config` - Configuration interfaces
- `com.inductiveautomation.historian.gateway.api.paths` - Path handling
- `com.inductiveautomation.historian.gateway.api.query` - Query processing
- `com.inductiveautomation.historian.gateway.api.query.browsing` - Query browsing
- `com.inductiveautomation.historian.gateway.api.query.processor` - Query execution
- `com.inductiveautomation.historian.gateway.api.storage` - Data storage abstractions
- `com.inductiveautomation.historian.gateway.api.storage.realtime` - Real-time storage
- `com.inductiveautomation.historian.gateway.api.storage.strategy` - Storage strategies

### Implementation Packages (Internal)
- `com.inductiveautomation.historian.gateway` - Core implementation
- `com.inductiveautomation.historian.gateway.distributed` - Distributed operations
- `com.inductiveautomation.historian.gateway.query` - Query implementation
- `com.inductiveautomation.historian.gateway.rpc` - RPC communication

### Legacy/Gateway Integration
- `com.inductiveautomation.ignition.gateway.historian` - Gateway historian services
- `com.inductiveautomation.ignition.common.historian` - Common historian interfaces
- `com.inductiveautomation.ignition.common.historian.rpc` - RPC layer

---

## Key Interfaces and Classes

### 1. Historian Interface

**Package**: `com.inductiveautomation.historian.gateway.api`

**Signature**:
```java
public interface Historian<S extends HistorianSettings>
```

**Required Methods**:
- `void startup() throws Exception` - Initialize historian
- `void shutdown()` - Cleanup resources
- `String getName()` - Return historian name
- `S getSettings()` - Return historian settings
- `Optional<QueryEngine> getQueryEngine()` - Provide query engine
- `Optional<StorageEngine> getStorageEngine()` - Provide storage engine
- `QualifiedPathAdapter getPathAdapter()` - Handle path normalization
- `boolean handleNameChange(String newName)` - Handle name changes
- `boolean handleSettingsChange(S newSettings)` - Handle setting changes

**Known Implementations**:
- `CoreHistorian` - Built-in database historian
- `CsvHistorian` - CSV file historian
- `InternalHistorian` - Internal system historian

---

### 2. AbstractHistorian

**Package**: `com.inductiveautomation.historian.gateway.api`

**Signature**:
```java
public abstract class AbstractHistorian<S extends HistorianSettings>
    extends java.lang.Object
    implements Historian<S>
```

**Constructor**:
```java
protected AbstractHistorian(GatewayContext context, String historianName)
```

**Protected Fields**:
- `LoggerEx logger` - Logger instance
- `GatewayContext context` - Gateway context
- `String historianName` - Historian name
- `S historianSettings` - Historian settings
- `boolean started` - Startup state

**Extension Points**:
- `void onStartup()` - Called during startup
- `void onShutdown()` - Called during shutdown

**Key Notes**:
- Does NOT extend `AbstractExtensionPoint`
- Provides base lifecycle management
- Subclasses must implement QueryEngine and/or StorageEngine

---

### 3. HistorianSettings Interface

**Package**: `com.inductiveautomation.historian.gateway.api.config`

**Type**: Marker interface

**Purpose**: Base interface for all historian configuration settings

**Usage**: Create a concrete implementation with your historian's configuration properties

---

### 4. QueryEngine Interface

**Package**: `com.inductiveautomation.historian.gateway.api.query`

**Purpose**: Handles reading historical data

**Key Methods**:
- `void browse(QualifiedPath root, BrowseFilter filter, BrowsePublisher publisher)` - Browse tags
- `void query(RawQueryOptions options, RawPointProcessor processor)` - Query raw data
- `void query(AggregatedQueryOptions options, AggregatedPointProcessor processor)` - Query aggregated data
- `void query(ComplexQueryOptions options, ComplexPointProcessor processor)` - Query complex data
- `Collection<? extends AggregationType> getNativeAggregates()` - Supported aggregations

**Abstract Base**: `AbstractQueryEngine` available for implementation

---

### 5. StorageEngine Interface

**Package**: `com.inductiveautomation.historian.gateway.api.storage`

**Purpose**: Handles writing historical data

**Key Methods** (all return `CompletionStage`):
- `CompletionStage<StorageResult<AtomicPoint<?>>> storeAtomic(List<AtomicPoint<?>>)` - Store atomic points
- `CompletionStage<StorageResult<C>> storeComplex(List<C>)` - Store complex data
- `CompletionStage<StorageResult<C>> applyChanges(List<C>)` - Apply data changes

**Abstract Base**: `AbstractStorageEngine` available for implementation

**Note**: All operations are asynchronous using Java's CompletionStage API

---

### 6. HistorianManager Interface

**Package**: `com.inductiveautomation.historian.gateway.api`

**Purpose**: Manages historian instances in the system

**Methods**:
- `Collection<String> getHistorianNames()` - List all historians
- `Optional<StorageEngine> getStorageEngine(String historianName)` - Get storage engine
- `Optional<QueryEngine> getQueryEngine(String historianName)` - Get query engine
- `static HistorianManager get(GatewayContext context)` - Get manager instance

**Implementation**: `HistorianManagerImpl` (internal)

**Key Finding**: **NO public `register()` or `add()` methods documented**

---

### 7. HistorianManagerImpl (Internal)

**Package**: `com.inductiveautomation.historian.gateway`

**Implements**:
- `HistorianManager`
- `TagHistoryManager`
- `ExtensionPointManager<HistorianExtensionPoint<?>>` ⚠️ **Key finding**

**Key Methods**:
- All query/storage methods for interacting with historians
- `getExtensionPointCollection()` - Returns collection of historian extension points

**Critical Finding**: Implements `ExtensionPointManager<HistorianExtensionPoint<?>>` which means historians are managed as extension points, but `HistorianExtensionPoint` is NOT in the public API documentation.

---

### 8. HistorianExtensionPoint ⚠️ (Not Publicly Documented)

**Package**: `com.inductiveautomation.historian.gateway.api` (inferred from manager)

**Status**: Referenced in `HistorianManagerImpl` but NOT listed in public JavaDoc

**Inferred Purpose**: Wrapper class that bridges `Historian` implementations to the extension point system

**Likely Extends**: `AbstractExtensionPoint<HistorianSettings>` (pattern from other extension points)

**This is the MISSING PIECE for registering custom historians**

---

## Extension Point System

### ExtensionPoint Interface

**Package**: `com.inductiveautomation.ignition.gateway.config`

**Purpose**: Contract for all configurable extension points

**Key Methods**:
- `resourceType()` - Resource type grouping
- `typeId()` - Unique identifier
- `name(Locale)` - Display name
- `decode(JsonElement)` / `encode(S)` - JSON serialization
- `defaultSettings()` - Default configuration
- `getSettingsValidator()` - Configuration validation
- `canCreate()` - Can create new instances

---

### AbstractExtensionPoint

**Package**: `com.inductiveautomation.ignition.gateway.config`

**Constructor**:
```java
protected AbstractExtensionPoint(String typeId, String nameKey, String descriptionKey)
```

**Known Subclasses**:
- `TagProviderExtensionPoint`
- `UserSourceExtensionPoint`
- `OpcConnectionExtensionPoint`
- `DeviceExtensionPoint`
- `EmailProfileExtensionPoint`

**Pattern**: All extension point types extend this base class

---

### ExtensionPointManager Interface

**Signature**:
```java
public interface ExtensionPointManager<E extends ExtensionPoint<?>>
```

**Methods**:
- `ResourceType getExtensionPointResourceType()` - Get resource type
- `ExtensionPointCollection<E> getExtensionPointCollection()` - Get all extension points

**Known Implementations**:
- `TagProviderManager`
- `UserSourceManager`
- `AlarmNotificationManager`
- `HistorianManagerImpl` ⚠️

---

### ExtensionPointCollection Interface

**Purpose**: "A collection of extension points of a particular category. For example, a collection of all registered historian providers."

**Methods**:
- `boolean hasType(String typeId)` - Check if type exists
- `Optional<E> getType(String typeId)` - Get extension point by ID
- `List<E> getTypes()` - Get all extension points

**Implementation**: `ImmutableExtensionPointCollection` (read-only)

---

## Module Registration Pattern

### GatewayModuleHook Interface

**Package**: `com.inductiveautomation.ignition.gateway.model`

**Key Method for Registration**:
```java
default List<? extends ExtensionPoint<?>> getExtensionPoints()
```

**Description**: "Returns a list of any extension points this module wants to add to any extension point systems. This list will be a mixed-type list. Extension point managers will filter through the list and find the types that they are interested in."

**Pattern**:
1. Module implements `getExtensionPoints()` in its `GatewayModuleHook`
2. Return a list of extension point instances
3. Ignition's extension point managers automatically discover and register relevant types

---

### HistorianGatewayHook (Built-in Historian Module)

**Package**: `com.inductiveautomation.historian.gateway`

**Extends**: `AbstractGatewayModuleHook`

**Key Methods**:
- `void setup(GatewayContext context)` - Initialize before registration
- `void startup(LicenseState)` - Start historian module
- `HistorianManagerImpl getHistorianManager()` - Get manager
- `void registerAggregationFunctions()` - Register custom aggregation functions
- `List<IdbMigrationStrategy> getRecordMigrationStrategies()` - Database migrations

**Key Finding**: Does NOT override `getExtensionPoints()` in the documented API, suggesting historians might be registered differently (database-driven?)

---

## Related Gateway Services

### TagHistoryManager Interface

**Package**: `com.inductiveautomation.ignition.gateway.historian`

**Access**: `GatewayContext.getTagHistoryManager()`

**Purpose**: Legacy interface for tag history operations

**Note**: Implemented by `HistorianManagerImpl` for backward compatibility

---

### Legacy Interfaces (Deprecated)

**Package**: `com.inductiveautomation.ignition.gateway.historian`

- `AnnotationQueryProvider` - ⚠️ Deprecated
- `AnnotationStorageProvider` - ⚠️ Deprecated
- `TagHistoryQueryInterface` - Legacy query interface
- `AssociatedHistoryQueryInterface` - Associated provider queries

**Migration Path**: Use new `Historian` API with `QueryEngine` and `StorageEngine`

---

## Data Types and Models

### Quality Codes

**Standard OPC Quality Codes**:
- `192` (0xC0) - Good quality
- `0` (0x00) - Bad quality
- `64` (0x40) - Uncertain quality

### Data Point Types

**AtomicPoint**: Single timestamp-value-quality tuple
**ComplexPoint**: Multi-value or structured data
**AggregatedDataPoint**: Pre-aggregated data with statistics

### Query Options

**RawQueryOptions**: Query raw data points
**AggregatedQueryOptions**: Query with aggregation (avg, min, max, etc.)
**ComplexQueryOptions**: Query complex data structures

---

## What's Missing / Unknown

### 1. HistorianExtensionPoint Implementation ⚠️ **CRITICAL**

**Problem**: The class that bridges `Historian` to the extension point system is not documented

**Evidence**:
- `HistorianManagerImpl` implements `ExtensionPointManager<HistorianExtensionPoint<?>>`
- `HistorianExtensionPoint` is NOT in the public JavaDoc
- No public registration methods in `HistorianManager`

**Possible Explanations**:
1. API is incomplete / internal class leaked into signature
2. Registration happens through database configuration, not programmatic API
3. Documentation is incomplete (likely - API only 5 months old)

---

### 2. Registration Mechanism

**Unknown**:
- How to create a `HistorianExtensionPoint` instance
- How to register it with the system
- Whether registration is programmatic or database-driven

**Attempts Made**:
- ✅ Searched entire JavaDoc for registration methods
- ✅ Checked `GatewayModuleHook.getExtensionPoints()` pattern
- ✅ Examined `HistorianManager` for add/register methods (none found)
- ✅ Checked `HistorianGatewayHook` for registration examples (none shown)

---

### 3. Configuration UI

**Unknown**:
- How to create configuration UI for historian settings
- What React components to use (`getWebUiComponent()` in ExtensionPoint)
- How settings are persisted in the database

---

### 4. PersistentRecord Integration

**Unknown**:
- Whether historians use `PersistentRecord` for configuration
- Database schema for historian storage
- Migration strategies for custom historians

---

## Experimental Approach (Option 3)

### Goal
Attempt to create a working historian registration based on patterns from other extension points

### Approach

1. **Create HistorianSettings Implementation**
   ```java
   public class FactryHistorianSettings implements HistorianSettings {
       private String proxyUrl;
       private int timeout;
       // ... configuration properties
   }
   ```

2. **Implement AbstractHistorian**
   ```java
   public class FactryHistorian extends AbstractHistorian<FactryHistorianSettings> {
       public FactryHistorian(GatewayContext context, String name) {
           super(context, name);
       }

       @Override
       protected void onStartup() { /* ... */ }

       @Override
       public Optional<QueryEngine> getQueryEngine() { /* ... */ }

       @Override
       public Optional<StorageEngine> getStorageEngine() { /* ... */ }
   }
   ```

3. **Create HistorianExtensionPoint (Experimental)**

   **Attempt A**: Extend AbstractExtensionPoint
   ```java
   public class FactryHistorianExtensionPoint
       extends AbstractExtensionPoint<FactryHistorianSettings> {

       public FactryHistorianExtensionPoint() {
           super("factry-historian",
                 "FactryHistorian.Name",
                 "FactryHistorian.Description");
       }

       // Implement required methods
   }
   ```

   **Attempt B**: Try to use internal HistorianExtensionPoint (if accessible)
   ```java
   // May not be accessible - internal class
   import com.inductiveautomation.historian.gateway.api.HistorianExtensionPoint;
   ```

4. **Register via getExtensionPoints()**
   ```java
   @Override
   public List<? extends ExtensionPoint<?>> getExtensionPoints() {
       return List.of(new FactryHistorianExtensionPoint());
   }
   ```

---

## Questions for Inductive Automation Support

1. **HistorianExtensionPoint**: Is `com.inductiveautomation.historian.gateway.api.HistorianExtensionPoint` part of the public API? If so, where is it documented?

2. **Registration**: What is the correct way to register a custom historian in Ignition 8.3+? Should we:
   - Return a HistorianExtensionPoint from `getExtensionPoints()`?
   - Use a different registration mechanism?
   - Configure historians through the database/UI only?

3. **Example Code**: Are there any example projects or code snippets showing how to implement a custom historian using the 8.3+ API?

4. **Documentation Timeline**: When will complete documentation for the Historian API be available?

5. **SDK Version**: We're using SDK 8.3.1 which is the correct version for Ignition 8.3+ development.

---

## Resources

### Official Documentation
- JavaDoc: https://files.inductiveautomation.com/sdk/javadoc/ignition83/8.3.1/
- SDK Docs: https://sdk-docs.inductiveautomation.com/
- User Manual: https://docs.inductiveautomation.com/docs/8.3/

### Community
- Forum: https://forum.inductiveautomation.com/
- GitHub (SDK Training): https://github.com/inductiveautomation/ignition-sdk-training
- GitHub (SDK Examples): https://github.com/inductiveautomation/ignition-sdk-examples

### Legacy Examples (Pre-8.3)
- Simple Tag History Provider: https://github.com/IgnitionModuleDevelopmentCommunity/simpletaghistoryprovider
  - ⚠️ **Warning**: For Ignition 7.9 only - API completely changed in 8.3

---

## Conclusion

The Ignition 8.3 Historian API provides a solid foundation with `AbstractHistorian`, `QueryEngine`, and `StorageEngine`, but the **registration mechanism is not documented**. The missing `HistorianExtensionPoint` class is the key piece preventing custom historian implementation.

This appears to be due to the API being very new (August 2025) rather than a fundamental limitation. Once Inductive Automation provides the missing documentation or confirms the registration pattern, implementation should be straightforward.

**Recommendation**: Contact Inductive Automation support for clarification before proceeding with production implementation.

**Feasibility**: High potential, but **blocked on missing documentation** - not a technical limitation but a documentation gap.
