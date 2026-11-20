package io.factry.historian.gateway;

import ch.qos.logback.classic.Level;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.gateway.config.ConfigurationManager;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.storeforward.StoreAndForwardManager;
import com.inductiveautomation.ignition.gateway.storeforward.engine.EngineInformation;
import com.inductiveautomation.ignition.gateway.storeforward.engine.EngineStatus;
import com.inductiveautomation.ignition.gateway.storeforward.resource.StoreAndForwardEngineSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renaming a profile leaves the data store and forward still holds for it in the engine named
 * after the old name (#36). The module keeps the ordinary engine (a third-party one would drop
 * tag data whenever the profile is stopped), so it must say what is left behind and what to do.
 */
class ProfileRenameTest {

    private final List<Resource> engines = new ArrayList<>();
    private int pending = 7;
    private int quarantined = 2;
    private FactryHistoryProvider provider;

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.shutdown();
        }
    }

    @Test
    void renameWarnsWhatTheOldEngineHolds() throws Exception {
        provider = started("factry-r");

        try (LogCapture log = new LogCapture(FactryHistoryProvider.class.getName(), Level.INFO)) {
            assertTrue(provider.handleNameChange("factry-r2"), "keeps running, and forwarding from the old engine");

            assertTrue(log.events().stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("Profile renamed to 'factry-r2'")
                    && e.getFormattedMessage().contains("engine 'factry-r', which holds 7 pending and 2 quarantined")
                    && e.getFormattedMessage().contains("renamed back to 'factry-r'")), log.text());
        }
    }

    @Test
    void renameOfAnEmptyEngineIsNoWarning() throws Exception {
        pending = 0;
        quarantined = 0;
        provider = started("factry-r");

        try (LogCapture log = new LogCapture(FactryHistoryProvider.class.getName(), Level.INFO)) {
            provider.handleNameChange("factry-r2");

            assertTrue(log.text().contains("Store and forward engine 'factry-r' is empty"), log.text());
            assertTrue(log.events().stream().noneMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("renamed")), log.text());
        }
    }

    @Test
    void renameInLetterCaseOnlyKeepsTheEngine() throws Exception {
        provider = started("factry-r");

        try (LogCapture log = new LogCapture(FactryHistoryProvider.class.getName(), Level.INFO)) {
            provider.handleNameChange("Factry-R");

            assertTrue(log.text().contains("keeps store and forward engine 'factry-r'"), log.text());
            assertTrue(log.events().stream().noneMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().contains("renamed")), log.text());
        }
    }

    @Test
    void theEngineStaysAnOrdinaryOne() throws Exception {
        provider = started("factry-r");

        assertEquals(1, engines.size());
        assertFalse(StoreAndForwardEngineSettings.getMeta().getCodec().decode(engines.get(0)).config().isThirdParty(),
                "a third-party engine stops with the profile and Ignition then drops tag data sent to it");
    }

    /** A profile started the way Ignition starts it; Factry is not reachable, which is fine here. */
    private FactryHistoryProvider started(String name) throws Exception {
        FactryHistoryProvider p = new FactryHistoryProvider(gatewayContext(), name, unreachableFactry());
        p.startup();
        return p;
    }

    private static FactryHistorianSettings unreachableFactry() {
        String payload = "{\"uuid\":\"collector-uuid\",\"name\":\"Ignition\","
                + "\"aud\":\"http://127.0.0.1\",\"grpc-port\":\"1\"}";
        FactryHistorianSettings settings = new FactryHistorianSettings();
        settings.setToken("e30." + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + ".sig");
        return settings;
    }

    private GatewayContext gatewayContext() {
        MetricRegistry registry = new MetricRegistry();
        StoreAndForwardManager storeAndForward = storeAndForwardManager();
        ConfigurationManager configuration = configurationManager();
        return (GatewayContext) Proxy.newProxyInstance(GatewayContext.class.getClassLoader(),
                new Class<?>[]{GatewayContext.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getMetricRegistry" -> registry;
                    case "getStoreAndForwardManager" -> storeAndForward;
                    case "getConfigurationManager" -> configuration;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Engines are found ignoring case, as Ignition's manager does. */
    private StoreAndForwardManager storeAndForwardManager() {
        return (StoreAndForwardManager) Proxy.newProxyInstance(StoreAndForwardManager.class.getClassLoader(),
                new Class<?>[]{StoreAndForwardManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getEngineInformation" -> engines.stream()
                            .filter(r -> r.getResourceName().equalsIgnoreCase((String) args[0]))
                            .findFirst()
                            .map(r -> EngineInformation.newBuilder().engineId(r.getResourceName())
                                    .engineStatus(EngineStatus.fullyOperationStatus())
                                    .storeMetric(new Meter()).forwardMetric(new Meter())
                                    .totalPending(pending).totalQuarantined(quarantined).build());
                    case "registerSink", "unregisterSink" -> null;
                    case "getQuarantineInterface" -> Optional.empty();
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    /** Saves what is pushed, like Ignition does before its listeners run. */
    private ConfigurationManager configurationManager() {
        return (ConfigurationManager) Proxy.newProxyInstance(ConfigurationManager.class.getClassLoader(),
                new Class<?>[]{ConfigurationManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getResources" -> new ArrayList<>(engines);
                    case "push" -> {
                        for (Object o : (List<?>) args[0]) {
                            engines.add(ChangeOperation.getResourceFromChange((ChangeOperation) o));
                        }
                        yield CompletableFuture.completedFuture(null);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }
}
