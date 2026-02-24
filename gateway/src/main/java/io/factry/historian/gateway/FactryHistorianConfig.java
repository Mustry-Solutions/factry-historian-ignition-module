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
 * The record is flat (no nesting) so the field names match FactryHistorianSettings
 * exactly. @FormCategory controls the visual grouping in the UI.
 */
public record FactryHistorianConfig(
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
        int timeoutMs,

        @FormCategory("Connection")
        @Label("gRPC Host*")
        @FormField(FormFieldType.TEXT)
        @DefaultValue("localhost")
        @Required
        @Description("Hostname of the gRPC proxy server")
        String grpcHost,

        @FormCategory("Connection")
        @Label("gRPC Port")
        @FormField(FormFieldType.NUMBER)
        @DefaultValue("9876")
        @Description("Port of the gRPC proxy server")
        int grpcPort,

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
) {
    /**
     * Convert this config record to FactryHistorianSettings.
     */
    public FactryHistorianSettings toSettings() {
        FactryHistorianSettings settings = new FactryHistorianSettings();
        settings.setUrl(url);
        settings.setTimeoutMs(timeoutMs);
        settings.setGrpcHost(grpcHost);
        settings.setGrpcPort(grpcPort);
        settings.setBatchSize(batchSize);
        settings.setBatchIntervalMs(batchIntervalMs);
        settings.setDebugLogging(debugLogging);
        return settings;
    }

    /**
     * Create a config record from FactryHistorianSettings.
     */
    public static FactryHistorianConfig fromSettings(FactryHistorianSettings settings) {
        return new FactryHistorianConfig(
                settings.getUrl(),
                settings.getTimeoutMs(),
                settings.getGrpcHost(),
                settings.getGrpcPort(),
                settings.getBatchSize(),
                settings.getBatchIntervalMs(),
                settings.isDebugLogging()
        );
    }
}
