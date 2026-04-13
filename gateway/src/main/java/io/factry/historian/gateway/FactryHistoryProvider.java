package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.AbstractHistorian;
import com.inductiveautomation.historian.gateway.api.query.QueryEngine;
import com.inductiveautomation.historian.gateway.api.storage.StorageEngine;
import com.inductiveautomation.historian.gateway.interop.TagHistoryDataSinkBridge;
import com.inductiveautomation.historian.gateway.interop.TagHistoryStorageEngineBridge;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.model.ProfileStatus;
import com.inductiveautomation.ignition.gateway.storeforward.StorageKey;
import com.inductiveautomation.ignition.gateway.storeforward.quarantine.QuarantineInterface;
import com.inductiveautomation.ignition.gateway.storeforward.quarantine.QuarantinedDataInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
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

    /** Cached status to avoid hitting gRPC on every gateway UI poll. */
    private volatile ProfileStatus cachedStatus = ProfileStatus.UNKNOWN;
    private volatile long statusCheckedAt = 0;
    private static final long STATUS_CACHE_MS = ModuleProperties.getStatusCacheMs();

    public FactryHistoryProvider(GatewayContext context, String historianName, FactryHistorianSettings settings) {
        super(context, historianName);
        this.context = context;
        this.settings = settings;
        settings.applyTokenDefaults(settings.getToken());
        settings.validate();
        this.metrics = new HistorianMetrics();

        this.grpcClient = new FactryGrpcClient(
                settings.getGrpcHost(),
                settings.getGrpcPort(),
                settings.getCollectorUUID(),
                settings.getToken(),
                settings.isUseTls(),
                settings.isSkipTlsVerification()
        );
        this.measurementCache = new MeasurementCache();

        this.queryEngine = new FactryQueryEngine(context, historianName, settings, grpcClient, measurementCache, metrics);
        this.storageEngine = new FactryStorageEngine(context, historianName, settings, grpcClient, measurementCache, metrics);

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

        measurementCache.refresh(grpcClient);
        logger.info("Measurement cache pre-populated with {} entries", measurementCache.size());

        // Set up Store & Forward if configured
        String sfEngine = settings.getStoreAndForwardEngine();
        if (sfEngine != null && !sfEngine.isBlank()) {
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

            // Log metrics summary every 30 seconds
            scheduledExecutor.scheduleWithFixedDelay(
                    metrics::logSummary, 30, 30, TimeUnit.SECONDS);

            // Periodic measurement cache refresh to detect deleted measurements
            long refreshInterval = ModuleProperties.getMeasurementCacheRefreshSeconds();
            scheduledExecutor.scheduleWithFixedDelay(
                    this::refreshMeasurementCache, refreshInterval, refreshInterval, TimeUnit.SECONDS);

            logger.info("Store-and-forward enabled via engine '{}', sink accepting: {}",
                    sfEngine, dataSinkBridge.isAccepting());
        } else {
            // Even without S&F, schedule metrics logging and cache refresh
            scheduledExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "factry-historian-scheduler");
                t.setDaemon(true);
                return t;
            });
            scheduledExecutor.scheduleWithFixedDelay(
                    metrics::logSummary, 30, 30, TimeUnit.SECONDS);

            long refreshInterval = ModuleProperties.getMeasurementCacheRefreshSeconds();
            scheduledExecutor.scheduleWithFixedDelay(
                    this::refreshMeasurementCache, refreshInterval, refreshInterval, TimeUnit.SECONDS);
        }

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

    @Override
    public boolean handleNameChange(String newName) {
        logger.info("Historian name changed: {} -> {}", historianName, newName);
        // Measurement names in Factry don't contain the historian profile name,
        // so no data migration is needed.
        // The framework updates the historianName field in AbstractHistorian.
        return true;
    }

    @Override
    public boolean handleSettingsChange(FactryHistorianSettings newSettings) {
        logger.info("Historian settings change: {}", newSettings);

        try {
            newSettings.applyTokenDefaults(newSettings.getToken());
            newSettings.validate();

            // Reconfigure the gRPC client (shuts down old channel, creates new one)
            grpcClient.reconfigure(
                    newSettings.getGrpcHost(),
                    newSettings.getGrpcPort(),
                    newSettings.getCollectorUUID(),
                    newSettings.getToken(),
                    newSettings.isUseTls(),
                    newSettings.isSkipTlsVerification()
            );

            // Refresh measurement cache from the new endpoint
            measurementCache.refresh(grpcClient);

            // Update settings references in the engines
            queryEngine.updateSettings(newSettings);
            storageEngine.updateSettings(newSettings);
            this.settings = newSettings;

            logger.info("Settings change applied successfully");
            return true;
        } catch (Exception e) {
            logger.error("Failed to apply settings change", e);
            return false;
        }
    }

    @Override
    public ProfileStatus getStatus() {
        if (!started) {
            return ProfileStatus.UNKNOWN;
        }

        long now = System.currentTimeMillis();
        if (now - statusCheckedAt < STATUS_CACHE_MS) {
            return cachedStatus;
        }

        statusCheckedAt = now;
        cachedStatus = grpcClient.testConnection()
                ? ProfileStatus.RUNNING
                : ProfileStatus.ERRORED;
        return cachedStatus;
    }

    private void retryQuarantinedData(String engineName) {
        try {
            // Test connection and re-enable forwarding if the server is back
            if (!grpcClient.isConnected()) {
                if (grpcClient.testConnection()) {
                    logger.info("Factry server is reachable again, re-enabling S&F forwarding");
                } else {
                    logger.debug("Factry server still unreachable, S&F will keep buffering");
                    return;
                }
            }

            Optional<QuarantineInterface<?>> qi =
                    context.getStoreAndForwardManager().getQuarantineInterface(engineName);
            if (qi.isPresent()) {
                int count = qi.get().getQuarantinedCount();
                if (count > 0) {
                    List<Integer> ids = qi.get().getQuarantinedDataInfo().stream()
                            .map(QuarantinedDataInfo::id)
                            .collect(Collectors.toList());
                    qi.get().retryQuarantined(ids);
                    logger.info("Retrying {} quarantined data entries for S&F engine '{}'",
                            ids.size(), engineName);
                }
            }
        } catch (Exception e) {
            logger.debug("Error retrying quarantined data", e);
        }
    }

    private void refreshMeasurementCache() {
        try {
            int before = measurementCache.size();
            measurementCache.refresh(grpcClient);
            int after = measurementCache.size();
            if (before != after) {
                logger.info("Measurement cache refreshed: {} -> {} entries", before, after);
            } else {
                logger.debug("Measurement cache refreshed: {} entries (unchanged)", after);
            }
        } catch (Exception e) {
            logger.debug("Error refreshing measurement cache", e);
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
