package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.AbstractHistorian;
import com.inductiveautomation.historian.gateway.api.query.QueryEngine;
import com.inductiveautomation.historian.gateway.api.storage.StorageEngine;
import com.inductiveautomation.historian.gateway.interop.TagHistoryDataSinkBridge;
import com.inductiveautomation.historian.gateway.interop.TagHistoryStorageEngineBridge;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.model.ProfileStatus;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
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
    private volatile FactryHistorianSettings settings;
    private final FactryGrpcClient grpcClient;
    private final MeasurementCache measurementCache;

    private TagHistoryStorageEngineBridge storageBridge;
    private TagHistoryDataSinkBridge dataSinkBridge;
    private ScheduledExecutorService quarantineRetryExecutor;

    /** Cached status to avoid hitting gRPC on every gateway UI poll. */
    private volatile ProfileStatus cachedStatus = ProfileStatus.UNKNOWN;
    private volatile long statusCheckedAt = 0;
    private static final long STATUS_CACHE_MS = 30_000; // 30 seconds

    public FactryHistoryProvider(GatewayContext context, String historianName, FactryHistorianSettings settings) {
        super(context, historianName);
        this.context = context;
        this.settings = settings;

        this.grpcClient = new FactryGrpcClient(
                settings.getGrpcHost(),
                settings.getGrpcPort(),
                settings.getCollectorUUID(),
                resolveToken(context, settings.getToken()),
                settings.isUseTls()
        );
        this.measurementCache = new MeasurementCache();

        this.queryEngine = new FactryQueryEngine(context, historianName, settings, grpcClient, measurementCache);
        this.storageEngine = new FactryStorageEngine(context, historianName, settings, grpcClient, measurementCache);

        logger.info("Factry Historian created: name={}, grpcTarget={}:{}, collectorUUID={}",
                historianName, settings.getGrpcHost(), settings.getGrpcPort(), settings.getCollectorUUID());
    }

    public FactryHistoryProvider(GatewayContext context, String historianName) {
        this(context, historianName, new FactryHistorianSettings());
    }

    @Override
    protected void onStartup() throws Exception {
        logger.info("========================================");
        logger.info("Factry Historian - Starting Up");
        logger.info("========================================");
        logger.info("Name: {}", historianName);
        logger.info("Settings: {}", settings);

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
            quarantineRetryExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "factry-quarantine-retry");
                t.setDaemon(true);
                return t;
            });
            quarantineRetryExecutor.scheduleWithFixedDelay(
                    () -> retryQuarantinedData(sfEngine), 30, 30, TimeUnit.SECONDS);

            logger.info("Store-and-forward enabled via engine '{}', sink accepting: {}",
                    sfEngine, dataSinkBridge.isAccepting());
        }

        logger.info("Factry Historian - Startup Complete");
    }

    @Override
    protected void onShutdown() {
        logger.info("========================================");
        logger.info("Factry Historian - Shutting Down");
        logger.info("========================================");
        logger.info("Name: {}", historianName);

        if (quarantineRetryExecutor != null) {
            quarantineRetryExecutor.shutdownNow();
            quarantineRetryExecutor = null;
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
            // Reconfigure the gRPC client (shuts down old channel, creates new one)
            grpcClient.reconfigure(
                    newSettings.getGrpcHost(),
                    newSettings.getGrpcPort(),
                    newSettings.getCollectorUUID(),
                    resolveToken(context, newSettings.getToken()),
                    newSettings.isUseTls()
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

    private static String resolveToken(GatewayContext context, SecretConfig secretConfig) {
        if (secretConfig == null) {
            return "";
        }
        try (Plaintext plaintext = Secret.create(context, secretConfig).getPlaintext()) {
            return plaintext.getAsString();
        } catch (Exception e) {
            logger.error("Failed to resolve token secret", e);
            return "";
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
