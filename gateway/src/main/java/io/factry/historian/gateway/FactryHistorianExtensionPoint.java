package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.Historian;
import com.inductiveautomation.historian.gateway.api.HistorianExtensionPoint;
import com.inductiveautomation.historian.gateway.api.HistorianProvider;
import com.inductiveautomation.historian.gateway.api.config.HistorianSettings;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.ExtensionPointConfig;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Extension point for the Factry Historian.
 *
 * This class registers the Factry Historian as a historian type that can be
 * created and managed through the Ignition Gateway UI at:
 * Config → Services → Historians → Create New Historian Profile
 */
public class FactryHistorianExtensionPoint extends HistorianExtensionPoint<HistorianSettings> {
    private static final Logger logger = LoggerFactory.getLogger(FactryHistorianExtensionPoint.class);

    /**
     * Unique type identifier for this historian.
     * This ID is used internally by Ignition to identify the historian type.
     */
    public static final String TYPE_ID = "factry-historian";

    /**
     * Display name shown in the Gateway UI.
     */
    public static final String DISPLAY_NAME = "Factry Historian";

    /**
     * Description shown in the Gateway UI.
     */
    public static final String DESCRIPTION = "External historian for Factry Historian system via REST API";

    /**
     * Creates a new Factry Historian extension point.
     */
    public FactryHistorianExtensionPoint() {
        super(TYPE_ID, DISPLAY_NAME, DESCRIPTION);
        logger.info("Factry Historian Extension Point created: type={}, name={}", TYPE_ID, DISPLAY_NAME);
    }

    /**
     * Factory method called by Ignition to create a new historian instance.
     *
     * This is called when:
     * - A user creates a new Factry Historian profile in the Gateway UI
     * - The Gateway loads existing Factry Historian profiles on startup
     *
     * @param context The gateway context
     * @param resource The decoded resource containing the historian configuration
     * @return A new Historian instance
     * @throws Exception if the historian cannot be created
     */
    @Override
    public Historian<HistorianSettings> createHistorianProvider(
            GatewayContext context,
            DecodedResource<ExtensionPointConfig<HistorianProvider, HistorianSettings>> resource
    ) throws Exception {
        logger.info("Creating Factry Historian provider from extension point");

        // Extract the historian name from the resource
        String historianName = resource.name();

        logger.info("Creating Factry Historian: name={}", historianName);

        // Create and return the historian provider
        // Note: Settings will be passed via the FactryHistoryProvider's configure method
        FactryHistoryProvider provider = new FactryHistoryProvider(context, historianName, new FactryHistorianSettings());

        logger.info("Factry Historian provider created successfully");
        return (Historian<HistorianSettings>) (Historian<?>) provider;
    }
}
