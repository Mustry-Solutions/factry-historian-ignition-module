package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.Historian;
import com.inductiveautomation.historian.gateway.api.HistorianExtensionPoint;
import com.inductiveautomation.historian.gateway.api.HistorianProvider;
import com.inductiveautomation.historian.gateway.api.config.HistorianSettings;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.ExtensionPointConfig;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.SchemaUtil;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.nav.ExtensionPointResourceForm;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.Set;

/**
 * Extension point for the Factry Historian.
 *
 * This class registers the Factry Historian as a historian type that can be
 * created and managed through the Ignition Gateway UI at:
 * Config → Services → Historians → Create New Historian Profile
 */
public class FactryHistorianExtensionPoint extends HistorianExtensionPoint<FactryHistorianSettings> {
    private static final Logger logger = LoggerFactory.getLogger(FactryHistorianExtensionPoint.class);

    /**
     * Unique type identifier for this historian.
     * This ID is used internally by Ignition to identify the historian type.
     */
    public static final String TYPE_ID = "factry-historian";

    /**
     * Display name for the historian type.
     * This appears in the Gateway UI when selecting a historian type.
     */
    public static final String DISPLAY_NAME = "Factry Historian";

    /**
     * Description for the historian type.
     * This appears in the Gateway UI when selecting a historian type.
     */
    public static final String DESCRIPTION = "External historian for Factry Historian system via REST API";

    /**
     * Creates a new Factry Historian extension point.
     * Passes literal display strings to the parent constructor.
     */
    public FactryHistorianExtensionPoint() {
        super(TYPE_ID, DISPLAY_NAME, DESCRIPTION);
        logger.info("========================================");
        logger.info("Factry Historian Extension Point created");
        logger.info("MODULE VERSION: {}", io.factry.historian.common.FactryHistorianModule.MODULE_VERSION);
        logger.info("Type: {}, Name: {}", TYPE_ID, DISPLAY_NAME);
        logger.info("========================================");
    }

    /**
     * Provides the default settings instance for the historian.
     * This is required for Ignition to know the concrete settings type
     * when decoding config JSON. Without this, "Unable to decode config: no settings type" occurs.
     */
    @Override
    public Optional<FactryHistorianSettings> defaultSettings() {
        return Optional.of(new FactryHistorianSettings());
    }

    /**
     * Provides the web UI component for configuring the historian.
     *
     * This method returns the form definition that Ignition uses to render
     * the configuration page in the Gateway UI.
     *
     * @param type The type of UI component requested
     * @return Optional containing the web UI component configuration
     */
    @Override
    public Optional<WebUiComponent> getWebUiComponent(ComponentType type) {
        logger.info("========================================");
        logger.info("getWebUiComponent called!");
        logger.info("Component Type: {}", type);
        logger.info("Resource Type: {}", resourceType());
        logger.info("TYPE_ID: {}", TYPE_ID);
        logger.info("========================================");

        try {
            var schema = SchemaUtil.fromType(FactryHistorianConfig.class);
            logger.info("Schema created successfully: {}", schema);

            var component = new ExtensionPointResourceForm(
                    resourceType(),  // Use the historian resource type from parent class
                    "Factry Historian Configuration",  // Form title
                    TYPE_ID,  // Extension point type ID
                    schema,  // Use config for profile parameter (try both instead of null)
                    schema,  // Our config schema
                    Set.of()  // No additional capabilities
            );

            logger.info("ExtensionPointResourceForm created successfully");
            return Optional.of(component);
        } catch (Exception e) {
            logger.error("Error creating web UI component", e);
            return Optional.empty();
        }
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
    public Historian<FactryHistorianSettings> createHistorianProvider(
            GatewayContext context,
            DecodedResource<ExtensionPointConfig<HistorianProvider, HistorianSettings>> resource
    ) throws Exception {
        logger.info("Creating Factry Historian provider from extension point");

        String historianName = resource.name();

        // The settings are decoded as our concrete FactryHistorianSettings type
        // thanks to defaultSettings() providing the type information
        FactryHistorianSettings settings = resource.config().settings()
                .filter(s -> s instanceof FactryHistorianSettings)
                .map(s -> (FactryHistorianSettings) s)
                .orElseGet(() -> {
                    logger.warn("Settings not of expected type, using defaults");
                    return new FactryHistorianSettings();
                });

        logger.info("Creating Factry Historian: name={}, settings={}", historianName, settings);

        FactryHistoryProvider provider = new FactryHistoryProvider(context, historianName, settings);

        logger.info("Factry Historian provider created successfully");
        return provider;
    }
}
