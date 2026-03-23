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
    private boolean useTls = false;
    private boolean skipTlsVerification = false;
    private SecretConfig token;

    /**
     * Name of the Store & Forward engine to use for buffering.
     * If empty, S&F is disabled and writes go directly to the gRPC server.
     */
    private String storeAndForwardEngine = "";

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

    public boolean isUseTls() {
        return useTls;
    }

    public void setUseTls(boolean useTls) {
        this.useTls = useTls;
    }

    public boolean isSkipTlsVerification() {
        return skipTlsVerification;
    }

    public void setSkipTlsVerification(boolean skipTlsVerification) {
        this.skipTlsVerification = skipTlsVerification;
    }

    public SecretConfig getToken() {
        return token;
    }

    public void setToken(SecretConfig token) {
        this.token = token;
    }

    public String getStoreAndForwardEngine() {
        return storeAndForwardEngine;
    }

    public void setStoreAndForwardEngine(String storeAndForwardEngine) {
        this.storeAndForwardEngine = storeAndForwardEngine;
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
                ", useTls=" + useTls +
                ", skipTlsVerification=" + skipTlsVerification +
                ", storeAndForwardEngine='" + storeAndForwardEngine + '\'' +
                '}';
    }
}
