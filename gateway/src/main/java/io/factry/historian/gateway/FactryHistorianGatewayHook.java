package io.factry.historian.gateway;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Gateway hook for the Factry Historian module.
 *
 * This is instantiated by Ignition when the module is loaded in the gateway scope.
 */
public class FactryHistorianGatewayHook extends AbstractGatewayModuleHook {
    private static final Logger logger = LoggerFactory.getLogger(FactryHistorianGatewayHook.class);

    private GatewayContext gatewayContext;
    private FactryHistoryProvider historyProvider;

    /**
     * Called before startup. This is the chance for the module to add its extension points
     * and update persistent records and schemas.
     */
    @Override
    public void setup(GatewayContext context) {
        logger.info("========================================");
        logger.info("Factry Historian Module - Setup Starting");
        logger.info("========================================");

        this.gatewayContext = context;

        // Create the history provider
        try {
            logger.info("Creating Factry History Provider...");
            historyProvider = new FactryHistoryProvider(
                    context,
                    FactryHistoryProvider.HISTORIAN_NAME,
                    new FactryHistorianSettings()
            );
            logger.info("History provider created: {}", historyProvider.getName());
            logger.info("Factry historian implementation is ready");

        } catch (Exception e) {
            logger.error("Failed to create history provider", e);
        }

        logger.info("Gateway context stored successfully");
        logger.info("Factry Historian Module - Setup Complete");
    }

    /**
     * Called to initialize the module. Will only be called once.
     */
    @Override
    public void startup(LicenseState activationState) {
        logger.info("========================================");
        logger.info("Factry Historian Module - Startup");
        logger.info("========================================");
        logger.info("License State: {}", activationState.toString());

        // Start the historian if created
        if (historyProvider != null) {
            try {
                logger.info("Starting historian...");
                historyProvider.startup();
                logger.info("Historian started successfully");
            } catch (Exception e) {
                logger.error("Failed to start historian", e);
            }
        }

        logger.info("Factry Historian Module - Startup Complete");
    }

    /**
     * Called to shutdown this module.
     */
    @Override
    public void shutdown() {
        logger.info("========================================");
        logger.info("Factry Historian Module - Shutdown");
        logger.info("========================================");

        // Clean up history provider
        if (historyProvider != null) {
            try {
                logger.info("Shutting down history provider...");
                historyProvider.shutdown();
                logger.info("History provider shutdown complete");
            } catch (Exception e) {
                logger.error("Error shutting down history provider", e);
            }
        }

        logger.info("Factry Historian Module - Shutdown Complete");
    }

    /**
     * Return true if this is a "free" module (does not participate in licensing).
     */
    @Override
    public boolean isFreeModule() {
        return true; // Free module for development - no license required
    }
}
