package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.Historian;
import com.inductiveautomation.historian.gateway.api.HistorianExtensionPoint;
import com.inductiveautomation.historian.gateway.api.HistorianProvider;
import com.inductiveautomation.historian.gateway.api.config.HistorianSettings;
import com.inductiveautomation.ignition.gateway.config.DecodedResource;
import com.inductiveautomation.ignition.gateway.config.ExtensionPointConfig;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.SchemaUtil;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import com.inductiveautomation.ignition.gateway.web.nav.ExtensionPointResourceForm;
import com.inductiveautomation.ignition.gateway.web.nav.WebUiComponent;
import com.inductiveautomation.ignition.common.gson.GsonBuilder;
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
    public static final String DISPLAY_NAME = "FactryHistorianExtensionPoint.HistorianType.Name";

    /**
     * Description key for the historian type (resolved via BundleUtil).
     */
    public static final String DESCRIPTION = "FactryHistorianExtensionPoint.HistorianType.Description";

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
     * Register the SecretConfig Gson adapter so the extension point can
     * serialize/deserialize FactryHistorianSettings (which contains a SecretConfig token field).
     */
    @Override
    protected void customizeGson(GsonBuilder builder) {
        builder.registerTypeAdapter(SecretConfig.class, new SecretConfig.GsonAdapter());
    }

    /**
     * Provide the settings type explicitly so the framework can decode config JSON.
     * This is separate from defaultSettings() so we can return empty defaults
     * without losing the type information.
     */
    @Override
    public Optional<Class<FactryHistorianSettings>> settingsType() {
        return Optional.of(FactryHistorianSettings.class);
    }

    /**
     * Return empty so the frontend's ExtensionPointResourceForm does not call
     * form.reset(defaultSettings) on mount — that reset wipes config.profile.type
     * from the form state, causing the edit sidebar to show
     * "Extension Point Form Not Found".  Schema-level defaults still populate
     * the create form fields correctly.
     */
    @Override
    public Optional<FactryHistorianSettings> defaultSettings() {
        return Optional.empty();
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
                    null,    // no profile-level parameters
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
