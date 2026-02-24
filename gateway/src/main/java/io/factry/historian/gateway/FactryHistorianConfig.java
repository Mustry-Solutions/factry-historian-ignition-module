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
 * The record is flat (no nesting) so the field names match FactryHistorianSettings
 * exactly. @FormCategory controls the visual grouping in the UI.
 */
public record FactryHistorianConfig(
        @FormCategory("Connection")
        @Label("Host*")
        @FormField(FormFieldType.TEXT)
        @DefaultValue("localhost")
        @Required
        @Description("Hostname of the Factry Historian server")
        String grpcHost,

        @FormCategory("Connection")
        @Label("Port")
        @FormField(FormFieldType.NUMBER)
        @DefaultValue("9876")
        @Description("gRPC port of the Factry Historian server")
        int grpcPort,

        @FormCategory("Advanced")
        @Label("Batch Size")
        @FormField(FormFieldType.NUMBER)
        @DefaultValue("100")
        @Description("Number of historical data points to collect before sending")
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
        @Description("Enable detailed debug logging")
        boolean debugLogging
) {
    public FactryHistorianSettings toSettings() {
        FactryHistorianSettings settings = new FactryHistorianSettings();
        settings.setGrpcHost(grpcHost);
        settings.setGrpcPort(grpcPort);
        settings.setBatchSize(batchSize);
        settings.setBatchIntervalMs(batchIntervalMs);
        settings.setDebugLogging(debugLogging);
        return settings;
    }

    public static FactryHistorianConfig fromSettings(FactryHistorianSettings settings) {
        return new FactryHistorianConfig(
                settings.getGrpcHost(),
                settings.getGrpcPort(),
                settings.getBatchSize(),
                settings.getBatchIntervalMs(),
                settings.isDebugLogging()
        );
    }
}
