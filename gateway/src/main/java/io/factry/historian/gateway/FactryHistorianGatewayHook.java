package io.factry.historian.gateway;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.gateway.config.ExtensionPoint;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;

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
    private final FactryHistorianExtensionPoint extensionPoint = new FactryHistorianExtensionPoint();

    /**
     * Called before startup. This is the chance for the module to add its extension points
     * and update persistent records and schemas.
     */
    @Override
    public void setup(GatewayContext context) {
        logger.info("========================================");
        logger.info("Factry Historian Module - Setup Starting");
        logger.info("MODULE VERSION: {}", io.factry.historian.common.FactryHistorianModule.MODULE_VERSION);
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
        logger.info("MODULE VERSION: {}", io.factry.historian.common.FactryHistorianModule.MODULE_VERSION);
        logger.info("License State: {}", activationState.toString());
        logger.info("========================================");

        try {
            // Create historian settings
            FactryHistorianSettings settings = new FactryHistorianSettings();
            settings.setUrl("http://factry-proxy:8111");  // Proxy server URL (Docker service name)
            settings.setGrpcHost("factry-proxy");          // gRPC host (Docker service name)
            settings.setGrpcPort(50051);                   // gRPC port
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

            // Start the historian - this should register it with the system
            historyProvider.startup();

            // The historian is now started and should be accessible via system.tag.* functions
            logger.info("Historian is running. Use system.tag.configure() in scripts to set historian.");

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
            logger.info("");
            logger.info("NOTE: If FactryHistorian doesn't appear in dropdown,");
            logger.info("the historian may need to be registered differently.");
            logger.info("Check Gateway logs for registration messages.");
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

    /**
     * Return extension points provided by this module.
     * This makes the Factry Historian appear in the "Create Historian" dropdown.
     *
     * Note: Clicking "Next" will show "Web UI Component type not found" error
     * because third-party modules cannot provide UI components yet.
     *
     * For testing, use Jython scripts to access the historian directly.
     * See: docs/historian_from_jython.md
     */
    @Override
    public List<? extends ExtensionPoint<?>> getExtensionPoints() {
        logger.debug("getExtensionPoints() called - returning Factry Historian extension point");
        return Collections.singletonList(extensionPoint);
    }

    /**
     * Get the running historian instance for Jython script access.
     * This allows scripts to bypass the tag system and directly use the historian.
     *
     * @return The FactryHistoryProvider instance, or null if not started
     */
    public FactryHistoryProvider getHistorian() {
        return historyProvider;
    }
}
