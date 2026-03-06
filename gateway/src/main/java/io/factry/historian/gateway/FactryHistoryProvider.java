package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.AbstractHistorian;
import com.inductiveautomation.historian.gateway.api.query.QueryEngine;
import com.inductiveautomation.historian.gateway.api.storage.StorageEngine;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.model.ProfileStatus;
import com.inductiveautomation.ignition.gateway.secrets.Plaintext;
import com.inductiveautomation.ignition.gateway.secrets.Secret;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class FactryHistoryProvider extends AbstractHistorian<FactryHistorianSettings> {
    private static final Logger logger = LoggerFactory.getLogger(FactryHistoryProvider.class);

    public static final String HISTORIAN_NAME = "FactryHistorian";
    public static final String HISTORIAN_ID = "factry-historian";

    private final FactryQueryEngine queryEngine;
    private final FactryStorageEngine storageEngine;
    private final FactryHistorianSettings settings;
    private final FactryGrpcClient grpcClient;
    private final MeasurementCache measurementCache;

    public FactryHistoryProvider(GatewayContext context, String historianName, FactryHistorianSettings settings) {
        super(context, historianName);
        this.settings = settings;

        this.grpcClient = new FactryGrpcClient(
                settings.getGrpcHost(),
                settings.getGrpcPort(),
                settings.getCollectorUUID(),
                resolveToken(context, settings.getToken())
        );
        this.measurementCache = new MeasurementCache();

        this.queryEngine = new FactryQueryEngine(context, historianName, settings);
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

        logger.info("Factry Historian - Startup Complete");
    }

    @Override
    protected void onShutdown() {
        logger.info("========================================");
        logger.info("Factry Historian - Shutting Down");
        logger.info("========================================");
        logger.info("Name: {}", historianName);
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
        return Optional.of(storageEngine);
    }

    @Override
    public boolean handleNameChange(String newName) {
        logger.info("Historian name change requested: {} -> {}", historianName, newName);
        return true;
    }

    @Override
    public boolean handleSettingsChange(FactryHistorianSettings newSettings) {
        logger.info("Historian settings change requested: {} -> {}", settings, newSettings);
        return true;
    }

    @Override
    public ProfileStatus getStatus() {
        return started ? ProfileStatus.RUNNING : ProfileStatus.UNKNOWN;
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
