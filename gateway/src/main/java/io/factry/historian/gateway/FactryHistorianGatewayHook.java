package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.HistorianGatewayHook;
import com.inductiveautomation.historian.gateway.HistorianManagerImpl;
import com.inductiveautomation.historian.gateway.api.HistorianManager;
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
 * This module registers a HistorianExtensionPoint with the Historian Core module,
 * allowing users to create Factry Historian instances through the Gateway UI at:
 * Config → Services → Historians → Create New Historian Profile
 */
public class FactryHistorianGatewayHook extends AbstractGatewayModuleHook {
    private static final Logger logger = LoggerFactory.getLogger(FactryHistorianGatewayHook.class);

    private GatewayContext gatewayContext;
    // Initialize extension point immediately so it's available when getExtensionPoints() is called
    private final FactryHistorianExtensionPoint extensionPoint = new FactryHistorianExtensionPoint();

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

        logger.info("Factry Historian extension point available");
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
        logger.info("Factry Historian Module - Startup Complete");
        logger.info("========================================");

        // Note: Historian instances are now managed by the Historian Core module
        // Users can create/start/stop historians through the Gateway UI
    }

    /**
     * Called to shutdown this module.
     */
    @Override
    public void shutdown() {
        logger.info("========================================");
        logger.info("Factry Historian Module - Shutdown");
        logger.info("Factry Historian Module - Shutdown Complete");
        logger.info("========================================");

        // Note: Historian instances are managed by the Historian Core module
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
     * This is how Ignition 8.3+ discovers and registers extension points.
     */
    @Override
    public List<? extends ExtensionPoint<?>> getExtensionPoints() {
        logger.info("getExtensionPoints() called - returning Factry Historian extension point");
        return Collections.singletonList(extensionPoint);
    }
}
