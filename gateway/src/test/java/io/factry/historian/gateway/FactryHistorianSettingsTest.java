package io.factry.historian.gateway;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class FactryHistorianSettingsTest {

    private FactryHistorianSettings validSettings() {
        FactryHistorianSettings s = new FactryHistorianSettings();
        s.setCollectorUUID("550e8400-e29b-41d4-a716-446655440000");
        s.setGrpcHost("historian.example.com");
        s.setGrpcPort(8001);
        s.setBatchSize(100);
        s.setBatchIntervalMs(5000);
        return s;
    }

    // --- valid settings ---

    @Test
    void validate_validSettings_noException() {
        assertDoesNotThrow(() -> validSettings().validate());
    }

    @Test
    void validate_defaults_failsOnMissingCollectorUUID() {
        FactryHistorianSettings s = new FactryHistorianSettings();
        // defaults have empty collectorUUID
        assertThrows(IllegalArgumentException.class, s::validate);
    }

    // --- collectorUUID ---

    @Test
    void validate_nullCollectorUUID() {
        FactryHistorianSettings s = validSettings();
        s.setCollectorUUID(null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, s::validate);
        assertTrue(e.getMessage().contains("Collector ID"));
    }

    @Test
    void validate_emptyCollectorUUID() {
        FactryHistorianSettings s = validSettings();
        s.setCollectorUUID("");
        assertThrows(IllegalArgumentException.class, s::validate);
    }

    @Test
    void validate_blankCollectorUUID() {
        FactryHistorianSettings s = validSettings();
        s.setCollectorUUID("   ");
        assertThrows(IllegalArgumentException.class, s::validate);
    }

    // --- grpcHost ---

    @Test
    void validate_nullHost() {
        FactryHistorianSettings s = validSettings();
        s.setGrpcHost(null);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, s::validate);
        assertTrue(e.getMessage().contains("Host"));
    }

    @Test
    void validate_emptyHost() {
        FactryHistorianSettings s = validSettings();
        s.setGrpcHost("");
        assertThrows(IllegalArgumentException.class, s::validate);
    }

    // --- grpcPort ---

    @Test
    void validate_portZero() {
        FactryHistorianSettings s = validSettings();
        s.setGrpcPort(0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, s::validate);
        assertTrue(e.getMessage().contains("Port"));
    }

    @Test
    void validate_portNegative() {
        FactryHistorianSettings s = validSettings();
        s.setGrpcPort(-1);
        assertThrows(IllegalArgumentException.class, s::validate);
    }

    @Test
    void validate_portTooHigh() {
        FactryHistorianSettings s = validSettings();
        s.setGrpcPort(65536);
        assertThrows(IllegalArgumentException.class, s::validate);
    }

    @Test
    void validate_portBoundaryLow() {
        FactryHistorianSettings s = validSettings();
        s.setGrpcPort(1);
        assertDoesNotThrow(s::validate);
    }

    @Test
    void validate_portBoundaryHigh() {
        FactryHistorianSettings s = validSettings();
        s.setGrpcPort(65535);
        assertDoesNotThrow(s::validate);
    }

    // --- batchSize ---

    @Test
    void validate_batchSizeZero() {
        FactryHistorianSettings s = validSettings();
        s.setBatchSize(0);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, s::validate);
        assertTrue(e.getMessage().contains("Batch size"));
    }

    @Test
    void validate_batchSizeNegative() {
        FactryHistorianSettings s = validSettings();
        s.setBatchSize(-10);
        assertThrows(IllegalArgumentException.class, s::validate);
    }

    @Test
    void validate_batchSizeOne() {
        FactryHistorianSettings s = validSettings();
        s.setBatchSize(1);
        assertDoesNotThrow(s::validate);
    }

    // --- batchIntervalMs ---

    @Test
    void validate_batchIntervalTooLow() {
        FactryHistorianSettings s = validSettings();
        s.setBatchIntervalMs(50);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, s::validate);
        assertTrue(e.getMessage().contains("Batch interval"));
    }

    @Test
    void validate_batchIntervalZero() {
        FactryHistorianSettings s = validSettings();
        s.setBatchIntervalMs(0);
        assertThrows(IllegalArgumentException.class, s::validate);
    }

    @Test
    void validate_batchIntervalMinimum() {
        FactryHistorianSettings s = validSettings();
        s.setBatchIntervalMs(100);
        assertDoesNotThrow(s::validate);
    }
}
