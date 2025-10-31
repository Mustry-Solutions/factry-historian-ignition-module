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
 *
 * This module creates a Factry Historian instance programmatically on startup,
 * as the Historian Extension Point API does not currently support configuration UI
 * for third-party modules.
 *
 * Proof of Concept Goals:
 * 1. Add Factry Historian to tags and display in PowerChart
 * 2. Query historical data from Golang proxy server
 * 3. Collect tag changes and send to Golang collector
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

        logger.info("Factry Historian Module - Setup Complete");
        logger.info("========================================");
    }

    /**
     * Called to initialize the module. Will only be called once.
     * Creates and starts the Factry Historian programmatically.
     */
    @Override
    public void startup(LicenseState activationState) {
        logger.info("========================================");
        logger.info("Factry Historian Module - Startup");
        logger.info("License State: {}", activationState.toString());
        logger.info("========================================");

        try {
            // Create historian settings
            FactryHistorianSettings settings = new FactryHistorianSettings();
            settings.setUrl("http://localhost:8111");  // Proxy server URL
            settings.setTimeoutMs(5000);
            settings.setBatchSize(100);
            settings.setBatchIntervalMs(5000);
            settings.setDebugLogging(true);  // Enable debug for POC

            logger.info("Creating Factry Historian with settings: {}", settings);

            // Create historian instance
            historyProvider = new FactryHistoryProvider(
                gatewayContext,
                "FactryHistorian",  // Historian name that users will see
                settings
            );

            logger.info("Starting Factry Historian...");

            // Start the historian
            historyProvider.startup();

            logger.info("========================================");
            logger.info("Factry Historian started successfully!");
            logger.info("Historian Name: FactryHistorian");
            logger.info("Proxy URL: {}", settings.getUrl());
            logger.info("========================================");
            logger.info("");
            logger.info("PROOF OF CONCEPT SETUP:");
            logger.info("1. Create a tag with History enabled");
            logger.info("2. Set History Provider to: FactryHistorian");
            logger.info("3. Add tag to PowerChart to view historical data");
            logger.info("4. Create memo tag and change values to test collector");
            logger.info("========================================");

        } catch (Exception e) {
            logger.error("Failed to start Factry Historian", e);
        }

        logger.info("Factry Historian Module - Startup Complete");
        logger.info("========================================");
    }

    /**
     * Called to shutdown this module.
     */
    @Override
    public void shutdown() {
        logger.info("========================================");
        logger.info("Factry Historian Module - Shutdown");
        logger.info("========================================");

        try {
            if (historyProvider != null) {
                logger.info("Stopping Factry Historian...");
                historyProvider.shutdown();
                logger.info("Factry Historian stopped successfully");
            }
        } catch (Exception e) {
            logger.error("Error stopping Factry Historian", e);
        }

        logger.info("Factry Historian Module - Shutdown Complete");
        logger.info("========================================");
    }

    /**
     * Return true if this is a "free" module (does not participate in licensing).
     */
    @Override
    public boolean isFreeModule() {
        return true; // Free module for development - no license required
    }
}
