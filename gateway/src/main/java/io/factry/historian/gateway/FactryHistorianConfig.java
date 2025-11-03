package io.factry.historian.gateway;

import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.DefaultValue;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Description;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormCategory;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.FormField;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Label;
import com.inductiveautomation.ignition.gateway.dataroutes.openapi.annotations.Required;
import com.inductiveautomation.ignition.gateway.web.nav.FormFieldType;

/**
 * Configuration record for the Factry Historian web UI form.
 *
 * This record defines the configuration fields that appear in the Gateway UI
 * when creating or editing a Factry Historian profile.
 *
 * Uses Java record pattern with annotations to automatically generate the web form.
 */
public record FactryHistorianConfig(Connection connection, Advanced advanced) {

    /**
     * Connection settings category
     */
    record Connection(
            @FormCategory("Connection")
            @Label("Proxy URL*")
            @FormField(FormFieldType.TEXT)
            @DefaultValue("http://localhost:8111")
            @Required
            @Description("URL of the Factry Historian proxy REST API endpoint")
            String url,

            @FormCategory("Connection")
            @Label("Timeout (ms)")
            @FormField(FormFieldType.NUMBER)
            @DefaultValue("5000")
            @Description("HTTP request timeout in milliseconds")
            int timeoutMs
    ) {}

    /**
     * Advanced settings category
     */
    record Advanced(
            @FormCategory("Advanced")
            @Label("Batch Size")
            @FormField(FormFieldType.NUMBER)
            @DefaultValue("100")
            @Description("Number of historical data points to collect before sending to the proxy")
            int batchSize,

            @FormCategory("Advanced")
            @Label("Batch Interval (ms)")
            @FormField(FormFieldType.NUMBER)
            @DefaultValue("5000")
            @Description("Time interval in milliseconds between batch sends")
            int batchIntervalMs,

            @FormCategory("Advanced")
            @Label("Debug Logging")
            @FormField(FormFieldType.CHECKBOX)
            @DefaultValue("false")
            @Description("Enable detailed debug logging for HTTP requests and responses")
            boolean debugLogging
    ) {}

    /**
     * Convert this config record to FactryHistorianSettings.
     *
     * @return FactryHistorianSettings instance with values from this config
     */
    public FactryHistorianSettings toSettings() {
        FactryHistorianSettings settings = new FactryHistorianSettings();
        settings.setUrl(connection.url());
        settings.setTimeoutMs(connection.timeoutMs());
        settings.setBatchSize(advanced.batchSize());
        settings.setBatchIntervalMs(advanced.batchIntervalMs());
        settings.setDebugLogging(advanced.debugLogging());
        return settings;
    }

    /**
     * Create a config record from FactryHistorianSettings.
     *
     * @param settings The settings to convert
     * @return FactryHistorianConfig instance
     */
    public static FactryHistorianConfig fromSettings(FactryHistorianSettings settings) {
        return new FactryHistorianConfig(
                new Connection(settings.getUrl(), settings.getTimeoutMs()),
                new Advanced(settings.getBatchSize(), settings.getBatchIntervalMs(), settings.isDebugLogging())
        );
    }
}
