package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.AbstractHistorian;
import com.inductiveautomation.historian.gateway.api.query.QueryEngine;
import com.inductiveautomation.historian.gateway.api.storage.StorageEngine;
import com.inductiveautomation.historian.gateway.interop.TagHistoryDataSinkBridge;
import com.inductiveautomation.historian.gateway.interop.TagHistoryStorageEngineBridge;
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceBuilder;
import com.inductiveautomation.ignition.gateway.config.ConfigurationManager;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.model.ProfileStatus;
import com.inductiveautomation.ignition.gateway.storeforward.StorageKey;
import com.inductiveautomation.ignition.gateway.storeforward.StoreAndForwardManager;
import com.inductiveautomation.ignition.gateway.storeforward.engine.EngineInformation;
import com.inductiveautomation.ignition.gateway.storeforward.quarantine.QuarantineInterface;
import com.inductiveautomation.ignition.gateway.storeforward.quarantine.QuarantinedDataInfo;
import com.inductiveautomation.ignition.gateway.storeforward.resource.StoreAndForwardEngineSettings;
import com.inductiveautomation.ignition.gateway.storeforward.sinks.DataSink;
import io.grpc.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

public class FactryHistoryProvider extends AbstractHistorian<FactryHistorianSettings> {
    private static final Logger logger = LoggerFactory.getLogger(FactryHistoryProvider.class);

    public static final String HISTORIAN_NAME = "FactryHistorian";
    public static final String HISTORIAN_ID = "factry-historian";

    private final GatewayContext context;
    private final FactryQueryEngine queryEngine;
    private final FactryStorageEngine storageEngine;
    private final HistorianMetrics metrics;
    private volatile FactryHistorianSettings settings;
    private final FactryGrpcClient grpcClient;
    private final MeasurementCache measurementCache;

    private TagHistoryStorageEngineBridge storageBridge;
    private TagHistoryDataSinkBridge dataSinkBridge;
    private ScheduledExecutorService scheduledExecutor;
    private volatile String sfEngineName;

    /** The profile status, from the connection check (on the scheduler) and the writes. */
    private final WriteHealth writeHealth;
    private static final long STATUS_CHECK_MS = ModuleProperties.getStatusCacheMs();

    public FactryHistoryProvider(GatewayContext context, String historianName, FactryHistorianSettings settings) {
        super(context, historianName);
        this.context = context;
        this.settings = settings;
        String token = settings.applyTokenDefaults(settings.getToken());
        settings.validate();
        this.metrics = new HistorianMetrics();
        this.writeHealth = new WriteHealth(historianName);

        this.grpcClient = new FactryGrpcClient(
                settings.getGrpcHost(),
                settings.getGrpcPort(),
                settings.getCollectorUUID(),
                token,
                settings.isUseTls(),
                settings.isSkipTlsVerification(),
                settings.getCustomCaCert()
        );
        this.measurementCache = new MeasurementCache();

        this.queryEngine = new FactryQueryEngine(context, historianName, settings, grpcClient, measurementCache, metrics);
        this.storageEngine = new FactryStorageEngine(context, historianName, settings, grpcClient, measurementCache, metrics,
                writeHealth);

        logger.info("Factry Historian created: name={}, grpcTarget={}:{}, collectorUUID={}",
                historianName, settings.getGrpcHost(), settings.getGrpcPort(), settings.getCollectorUUID());
    }

    public FactryHistoryProvider(GatewayContext context, String historianName) {
        this(context, historianName, new FactryHistorianSettings());
    }

    @Override
    protected void onStartup() throws Exception {
        logger.info("Factry Historian - Starting Up");
        logger.debug("Name: {}", historianName);
        logger.debug("Settings: {}", settings);

        // Register this collector with Factry so it shows as active
        try {
            var schema = io.factry.historian.proto.RegisterCollectorSchema.newBuilder()
                    .setCollectorType("ignition")
                    .setBuildVersion(FactryHistorianModule.MODULE_VERSION)
                    .setBuildOs(System.getProperty("os.name", "unknown"))
                    .setBuildArch(System.getProperty("os.arch", "unknown"))
                    .build();
            var collector = grpcClient.registerCollector(schema);
            logger.info("Collector registered with Factry: uuid={}, type={}, status={}",
                    collector.getUuid(), collector.getType(), collector.getStatus());

            // Set collector state to active/collecting
            var state = com.google.protobuf.Struct.newBuilder()
                    .putFields("status", com.google.protobuf.Value.newBuilder()
                            .setStringValue("Active").build())
                    .putFields("health", com.google.protobuf.Value.newBuilder()
                            .setStringValue("Collecting").build())
                    .build();
            grpcClient.updateCollectorState(state);
        } catch (Exception e) {
            logger.warn("Failed to register collector with Factry: " + e.getMessage());
        }

        try {
            measurementCache.refresh(grpcClient);
            logger.info("Measurement cache pre-populated with {} entries", measurementCache.size());
        } catch (Exception e) {
            logger.warn("Initial measurement cache refresh failed (Factry may still be starting): {}. " +
                    "Will retry via scheduled refresh.", e.getMessage());
        }

        // Set up Store & Forward — always enabled.
        // The S&F engine name matches the historian profile name because
        // system.tag.storeTagHistory() looks up the engine by historian name.
        final String sfEngine = historianName;

        // Ensure the S&F engine exists — create it if missing
        ensureStoreAndForwardEngine(sfEngine);

        StorageKey storageKey = StorageKey.of(sfEngine, historianName);

        // Sink bridge: wraps our storage engine, receives data from S&F
        dataSinkBridge = TagHistoryDataSinkBridge.getOrCreate(
                context, storageEngine, storageKey);
        context.getStoreAndForwardManager().registerSink(dataSinkBridge);

        // Workaround for Ignition 8.3.x: registerSink() only transitions the sink
        // to STARTED state. The S&F engine doesn't call initialize() on sinks
        // registered after the engine has started, leaving it stuck in "Storage Only".
        // Force initialization via reflection so the sink reaches ACCEPTING state.
        if (!dataSinkBridge.isAccepting()) {
            try {
                Method initMethod = dataSinkBridge.getClass().getSuperclass()
                        .getDeclaredMethod("initialize");
                initMethod.setAccessible(true);
                initMethod.invoke(dataSinkBridge);
            } catch (Exception e) {
                logger.error("Failed to initialize S&F data sink bridge", e);
            }
        }

        // Storage bridge: replaces direct storage, routes data into S&F
        storageBridge = TagHistoryStorageEngineBridge.getOrCreate(
                context, historianName, sfEngine);

        // Schedule automatic quarantine retry every 30 seconds.
        // Quarantined data is never retried automatically by S&F,
        // so we periodically move it back to pending for re-forwarding.
        scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "factry-historian-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduledExecutor.scheduleWithFixedDelay(
                () -> retryQuarantinedData(sfEngine), 30, 30, TimeUnit.SECONDS);

        // This collector's changed measurements (paused, deleted, ...) every 30 s, and a full
        // reload every 10 minutes (MeasurementCache.sync)
        long refreshInterval = ModuleProperties.getMeasurementCacheRefreshSeconds();
        scheduledExecutor.scheduleWithFixedDelay(
                this::refreshMeasurementCache, refreshInterval, refreshInterval, TimeUnit.SECONDS);

        this.sfEngineName = sfEngine;

        logger.info("Store-and-forward enabled via engine '{}', sink accepting: {}",
                sfEngine, dataSinkBridge.isAccepting());

        // Send periodic health updates to Factry (heartbeat)
        scheduledExecutor.scheduleWithFixedDelay(
                this::sendHealthUpdate, 0, 30, TimeUnit.SECONDS);

        // Check the connection and pause or resume the S&F sink on a schedule. getStatus() is
        // only called when someone opens the gateway UI, so it cannot be what resumes forwarding.
        scheduledExecutor.scheduleWithFixedDelay(
                this::checkConnection, STATUS_CHECK_MS, STATUS_CHECK_MS, TimeUnit.MILLISECONDS);

        // Prime the status cache now that the connection has just been exercised
        // (registerCollector + cache refresh above), so the gateway UI shows the
        // real status on its first poll instead of waiting for the cache to expire.
        refreshConnectionStatus();

        logger.info("Factry Historian - Startup Complete");
    }

    @Override
    protected void onShutdown() {
        logger.info("Factry Historian - Shutting Down");
        logger.debug("Name: {}", historianName);

        if (scheduledExecutor != null) {
            scheduledExecutor.shutdownNow();
            scheduledExecutor = null;
        }

        if (dataSinkBridge != null) {
            context.getStoreAndForwardManager().unregisterSink(dataSinkBridge);
            dataSinkBridge.onShutdown();
            dataSinkBridge = null;
        }
        storageBridge = null;

        storageEngine.shutdown();
        grpcClient.shutdown();
        logger.info("Factry Historian - Shutdown Complete");
    }

    @Override
    public FactryHistorianSettings getSettings() {
        return settings;
    }

    @Override
    public Optional<QueryEngine> getQueryEngine() {
        return Optional.of(queryEngine);
    }

    @Override
    public Optional<StorageEngine> getStorageEngine() {
        return Optional.of(storageBridge != null ? storageBridge : storageEngine);
    }

    /**
     * Store and forward keeps this profile's buffered data in an engine named after the profile.
     * Like Ignition's own historians (8.3.6 and newer), announce the rename so Ignition renames that
     * engine with its data, then return false so Ignition restarts the profile under the new name and
     * the new instance forwards from the renamed engine. Measurement names in Factry do not contain the
     * profile name, so Factry needs no change.
     */
    @Override
    public boolean handleNameChange(String newName) {
        logger.info("Historian name changed: {} -> {}", historianName, newName);
        writeHealth.rename(newName);
        if (dataSinkBridge != null) {
            renameStoreAndForwardSink(context.getStoreAndForwardManager(), dataSinkBridge, sfEngineName, newName,
                    SINK_LIFECYCLE_EVENT);
        }
        return false;
    }

    /** Ignition 8.3.6 added this; our SDK (and our minimum Ignition version) is older. */
    static final String SINK_LIFECYCLE_EVENT = "com.inductiveautomation.ignition.gateway.storeforward.SinkLifecycleEvent";

    /**
     * Sends {@code SinkLifecycleEvent.sinkRenamed(sink, newName)} by reflection, so the module still
     * loads on gateways older than 8.3.6. There, the data still buffered for the old name stays in the
     * old engine; say so, with how much it is.
     */
    static boolean renameStoreAndForwardSink(StoreAndForwardManager storeAndForwardManager, DataSink<?> sink,
                                             String engineName, String newName, String eventClassName) {
        Method sinkRenamed;
        Method handle;
        try {
            Class<?> eventClass = Class.forName(eventClassName, true, StoreAndForwardManager.class.getClassLoader());
            sinkRenamed = eventClass.getMethod("sinkRenamed", DataSink.class, String.class);
            handle = StoreAndForwardManager.class.getMethod("handleSinkLifecycleEvent", eventClass);
        } catch (ReflectiveOperationException | LinkageError e) {
            warnDataLeftBehind(storeAndForwardManager, engineName, newName,
                    "this Ignition version cannot move it (needs 8.3.6 or newer)");
            return false;
        }
        try {
            handle.invoke(storeAndForwardManager, sinkRenamed.invoke(null, sink, newName));
            logger.info("Store and forward engine '{}' handed over to the new name '{}'", engineName, newName);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            Throwable cause = e instanceof InvocationTargetException ? e.getCause() : e;
            logger.error("Could not move store and forward data from '{}' to '{}'", engineName, newName, cause);
            warnDataLeftBehind(storeAndForwardManager, engineName, newName, "the move failed");
            return false;
        }
    }

    private static void warnDataLeftBehind(StoreAndForwardManager storeAndForwardManager, String engineName,
                                           String newName, String why) {
        Optional<EngineInformation> info = Optional.empty();
        try {
            info = storeAndForwardManager.getEngineInformation(engineName, true);
        } catch (Exception e) {
            logger.debug("No engine information for '{}'", engineName, e);
        }
        logger.warn("Profile renamed to '{}', but {}: data still buffered in store and forward engine '{}' "
                        + "({} pending, {} quarantined) is not forwarded to Factry until the profile is renamed "
                        + "back to '{}'. Let store and forward empty before renaming a profile.",
                newName, why, engineName,
                info.map(i -> String.valueOf(i.totalPending())).orElse("unknown"),
                info.map(i -> String.valueOf(i.totalQuarantined())).orElse("unknown"),
                engineName);
    }

    @Override
    public boolean handleSettingsChange(FactryHistorianSettings newSettings) {
        logger.info("Historian settings change: {}", newSettings);

        try {
            String token = newSettings.applyTokenDefaults(newSettings.getToken());
            newSettings.validate();

            // Reconfigure the gRPC client (shuts down old channel, creates new one)
            grpcClient.reconfigure(
                    newSettings.getGrpcHost(),
                    newSettings.getGrpcPort(),
                    newSettings.getCollectorUUID(),
                    token,
                    newSettings.isUseTls(),
                    newSettings.isSkipTlsVerification(),
                    newSettings.getCustomCaCert()
            );
            // Judge and log refusals of writes afresh; before the refresh, which can throw.
            storageEngine.clearRefusals();
            writeHealth.clearWrites();

            // Refresh measurement cache from the new endpoint
            measurementCache.refresh(grpcClient);

            // Update settings references in the engines
            queryEngine.updateSettings(newSettings);
            storageEngine.updateSettings(newSettings);
            this.settings = newSettings;

            // Re-test against the new endpoint so the gateway UI reflects the change on its
            // next poll, not up to STATUS_CHECK_MS later.
            refreshConnectionStatus();

            logger.info("Settings change applied successfully");
            return true;
        } catch (Exception e) {
            logger.error("Failed to apply settings change", e);
            // No status to set: on false, Ignition removes this instance and starts a new one
            // with the new settings, whose own connection check then says what is wrong.
            return false;
        }
    }

    @Override
    public ProfileStatus getStatus() {
        return started ? writeHealth.status() : ProfileStatus.UNKNOWN;
    }

    private void checkConnection() {
        try {
            refreshConnectionStatus();
        } catch (Exception e) {
            // An exception would cancel the scheduled task, and forwarding would never resume.
            logger.warn("Connection check failed: " + e.getMessage());
        }
    }

    /**
     * Actively test the Factry connection, record it for the status, and toggle the S&F
     * sink to match the connection state.
     * <p>
     * Runs every {@link #STATUS_CHECK_MS} on the scheduler, and right after startup and
     * settings changes so the gateway UI reflects the new state on its next poll.
     */
    private void refreshConnectionStatus() {
        Status check = grpcClient.checkConnection();
        writeHealth.connectionChecked(check);
        boolean connected = check.isOk();

        // Toggle S&F sink: when Factry is down, stop accepting so points stay in pending.
        // When Factry is back, re-initialize so forwarding resumes.
        if (dataSinkBridge != null) {
            if (connected && !dataSinkBridge.isAccepting()) {
                logger.info("Factry check passes, re-enabling S&F sink");
                try {
                    java.lang.reflect.Method initMethod = dataSinkBridge.getClass().getSuperclass()
                            .getDeclaredMethod("initialize");
                    initMethod.setAccessible(true);
                    initMethod.invoke(dataSinkBridge);
                } catch (Exception e) {
                    logger.error("Failed to re-initialize S&F sink", e);
                }
            } else if (!connected && dataSinkBridge.isAccepting()) {
                logger.info("Factry check fails, pausing S&F sink to buffer points");
                try {
                    java.lang.reflect.Method uninitMethod = dataSinkBridge.getClass().getSuperclass()
                            .getDeclaredMethod("uninitialize");
                    uninitMethod.setAccessible(true);
                    uninitMethod.invoke(dataSinkBridge);
                } catch (Exception e) {
                    logger.error("Failed to uninitialize S&F sink", e);
                }
            }
        }
    }

    /**
     * Ensure that the named S&F engine exists in the gateway configuration.
     * If not, creates one with sensible defaults (SQLite-backed, matching
     * Ignition's default engine settings).
     */
    private void ensureStoreAndForwardEngine(String engineName) {
        ensureStoreAndForwardEngine(context.getConfigurationManager(), context.getStoreAndForwardManager(),
                engineName, ENGINE_CREATE_TIMEOUT_MS);
    }

    private static final long ENGINE_CREATE_TIMEOUT_MS = 10_000;

    enum EngineCreation { EXISTS, CREATED, CREATED_PENDING, FAILED }

    static EngineCreation ensureStoreAndForwardEngine(ConfigurationManager configurationManager,
                                                      StoreAndForwardManager storeAndForwardManager,
                                                      String engineName, long timeoutMs) {
        // The resource also covers engines not started yet (a third-party engine starts with its first sink)
        Optional<Resource> existing = findEngineResource(configurationManager, engineName);
        if (existing.isPresent() || storeAndForwardManager.getEngineInformation(engineName, true).isPresent()) {
            logger.debug("S&F engine '{}' already exists", engineName);
            existing.ifPresent(r -> markThirdParty(configurationManager, r));
            return EngineCreation.EXISTS;
        }

        logger.info("S&F engine '{}' does not exist — creating it automatically", engineName);
        try {
            // Third party: Ignition renames the engine, with its data, when the profile is renamed
            StoreAndForwardEngineSettings engineSettings = new StoreAndForwardEngineSettings().withIsThirdParty(true);

            ResourceBuilder builder = Resource.newBuilder();
            builder.setResourceCollectionName("core");
            builder.setResourcePath(StoreAndForwardEngineSettings.getType().childPath(engineName));
            builder.setApplicationScope("G");
            StoreAndForwardEngineSettings.getMeta().getCodec().encode(engineSettings, builder);
            Resource resource = builder.build();

            ChangeOperation createOp = ChangeOperation.newCreateOp(resource);
            configurationManager.push(List.of(createOp)).get(timeoutMs, TimeUnit.MILLISECONDS);

            logger.info("S&F engine '{}' created successfully", engineName);
            return EngineCreation.CREATED;
        } catch (TimeoutException e) {
            // push() writes the resource before it returns; its future only waits for the change
            // listeners. When this profile is itself being started from a config change, those
            // listeners queue behind us, so the wait times out although the engine is there.
            if (findEngineResource(configurationManager, engineName).isPresent()) {
                logger.info("S&F engine '{}' created; Ignition is still applying it", engineName);
                return EngineCreation.CREATED_PENDING;
            }
            logger.error("Failed to create S&F engine '{}' — store-and-forward may not work", engineName, e);
            return EngineCreation.FAILED;
        } catch (Exception e) {
            logger.error("Failed to create S&F engine '{}' — store-and-forward may not work", engineName, e);
            return EngineCreation.FAILED;
        }
    }

    private static Optional<Resource> findEngineResource(ConfigurationManager configurationManager, String engineName) {
        return configurationManager.getResources(StoreAndForwardEngineSettings.getType()).stream()
                .filter(r -> r.getResourceName().equalsIgnoreCase(engineName))
                .findFirst();
    }

    /**
     * Engines created by module versions before this one are not third party, and for those Ignition
     * renames only the sink inside the old engine, which the restarted profile never looks at. So mark
     * the engine now, long before anyone renames the profile. Ignition applies the change to a running
     * engine with configure(): no restart, and its buffered data stays where it is. Only the flag
     * changes; the other engine settings are kept. Not waited for, for the same reason as the create
     * (the listeners may queue behind this startup).
     */
    static void markThirdParty(ConfigurationManager configurationManager, Resource resource) {
        String engineName = resource.getResourceName();
        try {
            var codec = StoreAndForwardEngineSettings.getMeta().getCodec();
            StoreAndForwardEngineSettings settings = codec.decode(resource).config();
            if (settings.isThirdParty()) {
                return;
            }
            ResourceBuilder builder = resource.toBuilder();
            codec.encode(settings.withIsThirdParty(true), builder);
            configurationManager.push(List.of(ChangeOperation.newModifyOp(builder.build(), resource.getResourceSignature())))
                    .whenComplete((ignored, e) -> {
                        if (e != null) {
                            logger.warn("Could not mark S&F engine '{}' as third party; renaming the profile "
                                    + "will leave its buffered data behind: {}", engineName, e.toString());
                        }
                    });
            logger.info("S&F engine '{}' marked third party, so a profile rename takes its buffered data along",
                    engineName);
        } catch (Exception e) {
            logger.warn("Could not mark S&F engine '{}' as third party; renaming the profile will leave its "
                    + "buffered data behind: {}", engineName, e.toString());
        }
    }

    private void retryQuarantinedData(String engineName) {
        retryQuarantinedData(context.getStoreAndForwardManager(), grpcClient.isConnected(), writeHealth, engineName);
    }

    static void retryQuarantinedData(StoreAndForwardManager storeAndForwardManager, boolean connected,
                                     WriteHealth writeHealth, String engineName) {
        try {
            // checkConnection() re-enables forwarding once Factry is back; until then a retry
            // would only fail again.
            if (!connected) {
                logger.debug("Factry server unreachable, not retrying quarantined data yet");
                return;
            }

            Optional<QuarantineInterface<?>> qi = storeAndForwardManager.getQuarantineInterface(engineName);
            if (qi.isPresent()) {
                int count = qi.get().getQuarantinedCount();
                if (count > 0) {
                    List<Integer> ids = qi.get().getQuarantinedDataInfo().stream()
                            .map(QuarantinedDataInfo::id)
                            .collect(Collectors.toList());
                    qi.get().retryQuarantined(ids);
                    // Every 30 s while Factry does not store writes: WriteHealth logs that story.
                    String line = "Retrying {} quarantined data entries for S&F engine '{}'";
                    if (writeHealth.writesFailing()) {
                        logger.debug(line, ids.size(), engineName);
                    } else {
                        logger.info(line, ids.size(), engineName);
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("Error retrying quarantined data", e);
        }
    }

    private void sendHealthUpdate() {
        sendHealthUpdate(grpcClient, writeHealth);
    }

    /**
     * Tell Factry how collecting goes, in its own words so its collector list colours it:
     * Collecting (green), or Unstable (orange) while Factry does not store our writes. Factry already
     * sees an outage or a refused token, since this update cannot arrive then.
     */
    static void sendHealthUpdate(FactryGrpcClient grpcClient, WriteHealth writeHealth) {
        try {
            var update = io.factry.historian.proto.HealthUpdate.newBuilder()
                    .setHealth(writeHealth.health())
                    .setTimestamp(com.google.protobuf.util.Timestamps.fromMillis(System.currentTimeMillis()))
                    .build();
            var updates = io.factry.historian.proto.HealthUpdates.newBuilder()
                    .addHealthUpdates(update)
                    .build();
            grpcClient.updateHealth(updates);
        } catch (Exception e) {
            logger.warn("Failed to send health update: " + e.getMessage());
        }
    }

    private void refreshMeasurementCache() {
        try {
            int before = measurementCache.size();
            measurementCache.sync(grpcClient);
            int after = measurementCache.size();
            if (before != after) {
                logger.info("Measurement cache refreshed: {} -> {} entries", before, after);
            } else {
                logger.debug("Measurement cache refreshed: {} entries (unchanged)", after);
            }
        } catch (Exception e) {
            logger.warn("Failed to refresh measurement cache: " + e.getMessage());
        }
    }

    @Override
    public String toString() {
        return "FactryHistoryProvider{" +
                "name='" + historianName + '\'' +
                ", settings=" + settings +
                '}';
    }
}
