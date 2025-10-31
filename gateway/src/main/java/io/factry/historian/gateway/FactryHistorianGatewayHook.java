package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.HistorianGatewayHook;
import com.inductiveautomation.historian.gateway.HistorianManagerImpl;
import com.inductiveautomation.historian.gateway.api.HistorianManager;
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
 * This module registers a HistorianExtensionPoint with the Historian Core module,
 * allowing users to create Factry Historian instances through the Gateway UI at:
 * Config → Services → Historians → Create New Historian Profile
 */
public class FactryHistorianGatewayHook extends AbstractGatewayModuleHook {
    private static final Logger logger = LoggerFactory.getLogger(FactryHistorianGatewayHook.class);

    private GatewayContext gatewayContext;
    private FactryHistorianExtensionPoint extensionPoint;
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

        // Create the extension point - it will be discovered by the Historian Core module
        extensionPoint = new FactryHistorianExtensionPoint();

        logger.info("Factry Historian extension point created");
        logger.info("Extension point type: {}", FactryHistorianExtensionPoint.TYPE_ID);

        logger.info("Factry Historian Module - Setup Complete");
        logger.info("========================================");
    }

    /**
     * Get the FactryHistorianExtensionPoint for use by the Historian Core module.
     * @return The extension point instance
     */
    public FactryHistorianExtensionPoint getExtensionPoint() {
        return extensionPoint;
    }

    /**
     * Called to initialize the module. Will only be called once.
     */
    @Override
    public void startup(LicenseState activationState) {
        logger.info("========================================");
        logger.info("Factry Historian Module - Startup");
        logger.info("License State: {}", activationState.toString());

        // WORKAROUND: Create historian instance programmatically
        // Since we cannot register the extension point in the UI dropdown,
        // we create a historian instance directly
        try {
            logger.info("Creating Factry Historian instance programmatically...");

            // Create default settings
            FactryHistorianSettings settings = new FactryHistorianSettings();
            settings.setProxyUrl("http://factry-proxy:8080"); // Default value
            settings.setDebugLogging(true);

            // Create the historian provider
            historyProvider = new FactryHistoryProvider(
                gatewayContext,
                "FactryHistorian",
                settings
            );

            // Start the historian
            historyProvider.startup();

            logger.info("Factry Historian instance created and started successfully");
            logger.info("Historian name: FactryHistorian");
            logger.info("Proxy URL: {}", settings.getProxyUrl());

        } catch (Exception e) {
            logger.error("Failed to create Factry Historian instance", e);
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

        // Shutdown the historian instance if it was created
        if (historyProvider != null) {
            try {
                logger.info("Shutting down Factry Historian instance...");
                historyProvider.shutdown();
                logger.info("Factry Historian instance shutdown complete");
            } catch (Exception e) {
                logger.error("Error shutting down Factry Historian instance", e);
            }
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
