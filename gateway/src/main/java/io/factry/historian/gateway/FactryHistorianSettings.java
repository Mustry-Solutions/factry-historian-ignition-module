package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.config.HistorianSettings;

/**
 * Configuration settings for the Factry Historian.
 *
 * This class holds all configuration properties needed to connect to the
 * external Factry Historian system via the proxy REST API.
 */
public class FactryHistorianSettings implements HistorianSettings {

    /**
     * URL of the proxy REST API endpoint (e.g., http://localhost:8111)
     */
    private String url = "http://localhost:8111";

    /**
     * Timeout for HTTP requests in milliseconds
     */
    private int timeoutMs = 5000;

    /**
     * Batch size for storing historical data points
     */
    private int batchSize = 100;

    /**
     * Batch interval in milliseconds - how often to send batched data
     */
    private int batchIntervalMs = 5000;

    /**
     * gRPC host for the proxy server
     */
    private String grpcHost = "localhost";

    /**
     * gRPC port for the proxy server
     */
    private int grpcPort = 50051;

    /**
     * Enable debug logging for HTTP requests
     */
    private boolean debugLogging = false;

    /**
     * No-arg constructor required for serialization
     */
    public FactryHistorianSettings() {
    }

    // Getters and setters

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public int getBatchIntervalMs() {
        return batchIntervalMs;
    }

    public void setBatchIntervalMs(int batchIntervalMs) {
        this.batchIntervalMs = batchIntervalMs;
    }

    public String getGrpcHost() {
        return grpcHost;
    }

    public void setGrpcHost(String grpcHost) {
        this.grpcHost = grpcHost;
    }

    public int getGrpcPort() {
        return grpcPort;
    }

    public void setGrpcPort(int grpcPort) {
        this.grpcPort = grpcPort;
    }

    public boolean isDebugLogging() {
        return debugLogging;
    }

    public void setDebugLogging(boolean debugLogging) {
        this.debugLogging = debugLogging;
    }

    @Override
    public String toString() {
        return "FactryHistorianSettings{" +
                "url='" + url + '\'' +
                ", grpcHost='" + grpcHost + '\'' +
                ", grpcPort=" + grpcPort +
                ", timeoutMs=" + timeoutMs +
                ", batchSize=" + batchSize +
                ", batchIntervalMs=" + batchIntervalMs +
                ", debugLogging=" + debugLogging +
                '}';
    }
}
