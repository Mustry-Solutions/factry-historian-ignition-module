package io.factry.historian.gateway;

import com.inductiveautomation.historian.gateway.api.config.HistorianSettings;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;

public class FactryHistorianSettings implements HistorianSettings {

    private String collectorUUID = "";
    private int batchSize = 100;
    private int batchIntervalMs = 5000;
    private String grpcHost = "localhost";
    private int grpcPort = 9876;
    private boolean debugLogging = false;
    private SecretConfig token;

    public FactryHistorianSettings() {
    }

    public String getCollectorUUID() {
        return collectorUUID;
    }

    public void setCollectorUUID(String collectorUUID) {
        this.collectorUUID = collectorUUID;
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

    public SecretConfig getToken() {
        return token;
    }

    public void setToken(SecretConfig token) {
        this.token = token;
    }

    @Override
    public String toString() {
        return "FactryHistorianSettings{" +
                "collectorUUID='" + collectorUUID + '\'' +
                ", grpcHost='" + grpcHost + '\'' +
                ", grpcPort=" + grpcPort +
                ", batchSize=" + batchSize +
                ", batchIntervalMs=" + batchIntervalMs +
                ", debugLogging=" + debugLogging +
                '}';
    }
}
