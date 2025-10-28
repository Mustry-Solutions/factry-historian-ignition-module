package io.factry.historian.gateway;

import com.inductiveautomation.ignition.gateway.config.AbstractExtensionPoint;
import com.google.gson.JsonElement;

import java.util.Locale;

/**
 * Extension point wrapper for the Factry Historian.
 *
 * EXPERIMENTAL: This class attempts to bridge the Factry Historian implementation
 * to Ignition's extension point system. This is based on patterns from other
 * extension points but may not be the correct approach for historians in Ignition 8.3+.
 *
 * The actual HistorianExtensionPoint class exists but is not documented in the public API.
 */
public class FactryHistorianExtensionPoint extends AbstractExtensionPoint<FactryHistorianSettings> {

    public static final String TYPE_ID = "factry-historian";
    public static final String NAME_KEY = "FactryHistorian.Name";
    public static final String DESCRIPTION_KEY = "FactryHistorian.Description";

    public FactryHistorianExtensionPoint() {
        super(TYPE_ID, NAME_KEY, DESCRIPTION_KEY);
    }

    @Override
    public String name(Locale locale) {
        return "Factry Historian";
    }

    @Override
    public String description(Locale locale) {
        return "Custom historian that stores tag history data in external Factry Historian system via REST API";
    }

    @Override
    public FactryHistorianSettings decode(JsonElement jsonElement) {
        // TODO: Implement proper JSON deserialization
        // For now, return default settings
        return new FactryHistorianSettings();
    }

    @Override
    public JsonElement encode(FactryHistorianSettings settings) {
        // TODO: Implement proper JSON serialization
        // For now, return empty JSON object
        return new com.google.gson.JsonObject();
    }

    @Override
    public FactryHistorianSettings defaultSettings() {
        return new FactryHistorianSettings();
    }

    @Override
    public Class<FactryHistorianSettings> settingsType() {
        return FactryHistorianSettings.class;
    }

    @Override
    public boolean canCreate() {
        // Allow users to create new instances of this historian
        return true;
    }
}
