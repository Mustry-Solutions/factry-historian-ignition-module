package io.factry.historian.gateway;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import javax.servlet.http.HttpServletResponse;

import com.inductiveautomation.ignition.common.licensing.LicenseState;
import com.inductiveautomation.ignition.common.project.resource.adapter.ResourceTypeAdapterRegistry;
import com.inductiveautomation.ignition.gateway.config.ExtensionPoint;
import com.inductiveautomation.ignition.gateway.dataroutes.RouteGroup;
import com.inductiveautomation.ignition.gateway.model.AbstractGatewayModuleHook;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.web.models.ConfigCategory;
import com.inductiveautomation.ignition.gateway.web.models.IConfigTab;
import com.inductiveautomation.ignition.gateway.web.models.SystemMap;
import com.inductiveautomation.ignition.gateway.web.pages.config.overviewmeta.ConfigOverviewContributor;
import com.inductiveautomation.ignition.gateway.web.pages.status.overviewmeta.OverviewContributor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class which is instantiated by the Ignition platform when the module is loaded in the gateway scope.
 */
public class FactryHistorianGatewayHook extends AbstractGatewayModuleHook {
    private static final Logger logger = LoggerFactory.getLogger(FactryHistorianGatewayHook.class);

    private GatewayContext gatewayContext;
    private FactryHistoryProvider historyProvider;
    /**
     * Called to before startup. This is the chance for the module to add its extension points and update persistent
     * records and schemas. None of the managers will be started up at this point, but the extension point managers will
     * accept extension point types.
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
            logger.info("Attempting to register with extension point system...");
            // Registration will be attempted via getExtensionPoints() method

        } catch (Exception e) {
            logger.error("Failed to create history provider", e);
        }

        logger.info("Gateway context stored successfully");
        logger.info("Factry Historian Module - Setup Complete");
    }

    /**
     * Called to initialize the module. Will only be called once. Persistence interface is available, but only in
     * read-only mode.
     */
    @Override
    public void startup(LicenseState activationState) {
        logger.info("========================================");
        logger.info("Factry Historian Module - Startup");
        logger.info("========================================");
        logger.info("License State: {}", activationState.toString());

        // Future: Initialize history provider and storage provider here

        logger.info("Factry Historian Module - Startup Complete");
    }

    /**
     * Called to shutdown this module. Note that this instance will never be started back up - a new one will be created
     * if a restart is desired
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
     * EXPERIMENTAL: Attempt to register our historian as an extension point.
     *
     * This follows the pattern from other extension points (TagProvider, UserSource, etc.)
     * but may not be the correct mechanism for historians in Ignition 8.3+.
     *
     * @return list containing our historian extension point
     */
    @Override
    public List<? extends ExtensionPoint<?>> getExtensionPoints() {
        logger.info("getExtensionPoints() called - attempting to register Factry Historian");
        try {
            FactryHistorianExtensionPoint extensionPoint = new FactryHistorianExtensionPoint();
            logger.info("Created extension point: {}", extensionPoint);
            return Collections.singletonList(extensionPoint);
        } catch (Exception e) {
            logger.error("Failed to create extension point", e);
            return Collections.emptyList();
        }
    }

    /**
     * A list (may be null or empty) of panels to display in the config section. Note that any config panels that are
     * part of a category that doesn't exist already or isn't included in {@link #getConfigCategories()} will
     * <i>not be shown</i>.
     */
    @Override
    public List<? extends IConfigTab> getConfigPanels() {
        return null;
    }

    /**
     * A list (may be null or empty) of custom config categories needed by any panels returned by  {@link
     * #getConfigPanels()}
     */
    @Override
    public List<ConfigCategory> getConfigCategories() {
        return null;
    }

    /**
     * @return the path to a folder in one of the module's gateway jar files that should be mounted at
     * /res/module-id/foldername
     */
    @Override
    public Optional<String> getMountedResourceFolder() {
        return Optional.empty();
    }

    /**
     * Provides a chance for the module to mount any route handlers it wants. These will be active at
     * <tt>/main/data/module-id/*</tt> See {@link RouteGroup} for details. Will be called after startup().
     */
    @Override
    public void mountRouteHandlers(RouteGroup routes) {

    }

    /**
     * Used by the mounting underneath /res/module-id/* and /main/data/module-id/* as an alternate mounting path instead
     * of your module id, if present.
     */
    @Override
    public Optional<String> getMountPathAlias() {
        return Optional.empty();
    }

    /**
     * @return {@code true} if this is a "free" module, i.e. it does not participate in the licensing system. This is
     * equivalent to the now defunct FreeModule attribute that could be specified in module.xml.
     */
    @Override
    public boolean isFreeModule() {
        return true; // Free module for development - no license required
    }

    /**
     * Implement this method to contribute meta data to the Status section's Systems / Overview page.
     */
    @Override
    public Optional<OverviewContributor> getStatusOverviewContributor() {
        return Optional.empty();
    }

    /**
     * Implement this method to contribute meta data to the Configure section's Overview page.
     */
    @Override
    public Optional<ConfigOverviewContributor> getConfigOverviewContributor() {
        return Optional.empty();
    }

    /**
     * Register any {@link ResourceTypeAdapter}s this module needs with with {@code registry}.
     * <p>
     * ResourceTypeAdapters are used to adapt a legacy (7.9 or prior) resource type name or payload into a nicer format
     * for the Ignition 8.0 project resource system.Ò Only override this method for modules that aren't known by the
     * {@link ResourceTypeAdapterRegistry} already.
     * <p>
     * <b>This method is called before {@link #setup(GatewayContext)} or {@link #startup(LicenseState)}.</b>
     *
     * @param registry the shared {@link ResourceTypeAdapterRegistry} instance.
     */
    @Override
    public void initializeResourceTypeAdapterRegistry(ResourceTypeAdapterRegistry registry) {

    }

    /**
     * Called prior to a 'mounted resource request' being fulfilled by requests to the mounted resource servlet serving
     * resources from /res/module-id/ (or /res/alias/ if {@link GatewayModuleHook#getMountPathAlias} is implemented). It
     * is called after the target resource has been successfully located.
     *
     * <p>
     * Primarily intended as an opportunity to amend/alter the response's headers for purposes such as establishing
     * Cache-Control. By default, Ignition sets no additional headers on a resource request.
     * </p>
     *
     * @param resourcePath path to the resource being returned by the mounted resource request
     * @param response     the response to read/amend.
     */
    @Override
    public void onMountedResourceRequest(String resourcePath, HttpServletResponse response) {

    }
}
