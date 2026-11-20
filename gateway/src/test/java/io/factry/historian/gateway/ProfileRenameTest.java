package io.factry.historian.gateway;

import ch.qos.logback.classic.Level;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricRegistry;
import com.inductiveautomation.ignition.common.resourcecollection.ChangeOperation;
import com.inductiveautomation.ignition.common.resourcecollection.Resource;
import com.inductiveautomation.ignition.common.resourcecollection.ResourceBuilder;
import com.inductiveautomation.ignition.gateway.config.ConfigurationManager;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.storeforward.SinkLifecycleEvent;
import com.inductiveautomation.ignition.gateway.storeforward.StorageKey;
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
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Renaming a profile must take the data still buffered in store and forward along (#36). Ignition
 * keeps it in an engine named after the profile. Its own historians (8.3.6) announce the rename with
 * {@code SinkLifecycleEvent.sinkRenamed} and restart under the new name; for a third-party engine named
 * after the old sink, Ignition then renames the whole engine, data and all.
 */
class ProfileRenameTest {

    private final List<Resource> engines = new ArrayList<>();
    private final List<ChangeOperation> pushed = new CopyOnWriteArrayList<>();
    private final List<Object> sinkEvents = new CopyOnWriteArrayList<>();
    private FactryHistoryProvider provider;

    @AfterEach
    void tearDown() {
        if (provider != null) {
            provider.shutdown();
        }
    }

    @Test
    void renameMovesTheBufferedDataAndRestartsTheProfile() throws Exception {
        provider = started("factry-r");

        boolean keepRunning = provider.handleNameChange("factry-r2");

        assertFalse(keepRunning, "Ignition must restart the profile, so it forwards from the renamed engine");
        assertEquals(1, sinkEvents.size(), "one rename announced to store and forward");
        SinkLifecycleEvent.SinkRenamed renamed = assertInstanceOf(SinkLifecycleEvent.SinkRenamed.class, sinkEvents.get(0));
        assertEquals(StorageKey.of("factry-r", "factry-r"), renamed.oldStorageKey());
        assertEquals("factry-r2", renamed.newSinkId());
    }

    @Test
    void newEngineIsThirdParty() throws Exception {
        provider = started("factry-r");

        assertEquals(1, engines.size());
        assertTrue(settings(engines.get(0)).isThirdParty(),
                "Ignition renames only a third-party engine together with its sink");
    }

    @Test
    void existingEngineIsMarkedThirdPartyAndKeepsItsSettings() throws Exception {
        StoreAndForwardEngineSettings before = new StoreAndForwardEngineSettings(1234L, 5678L);
        engines.add(engineResource("factry-r", before));

        try (LogCapture log = new LogCapture(FactryHistoryProvider.class.getName(), Level.INFO)) {
            provider = started("factry-r");
            assertTrue(log.text().contains("S&F engine 'factry-r' marked third party"), log.text());
        }

        assertEquals(1, pushed.size(), "one change: the existing engine, no new one");
        ChangeOperation op = pushed.get(0);
        assertInstanceOf(ChangeOperation.ModifyResourceOperation.class, op);
        StoreAndForwardEngineSettings after = settings(ChangeOperation.getResourceFromChange(op));
        assertEquals(before.withIsThirdParty(true), after, "only the third-party flag changes");
    }

    @Test
    void thirdPartyEngineIsLeftAlone() throws Exception {
        engines.add(engineResource("factry-r", new StoreAndForwardEngineSettings().withIsThirdParty(true)));

        provider = started("factry-r");

        assertTrue(pushed.isEmpty(), "nothing to change: " + pushed);
    }

    @Test
    void gatewayWithoutTheRenameEventWarnsWhatIsLeftBehind() throws Exception {
        provider = started("factry-r");

        try (LogCapture log = new LogCapture(FactryHistoryProvider.class.getName(), Level.INFO)) {
            boolean moved = FactryHistoryProvider.renameStoreAndForwardSink(storeAndForwardManager(), null,
                    "factry-r", "factry-r2", "com.inductiveautomation.ignition.gateway.storeforward.NotInThisVersion");

            assertFalse(moved);
            assertTrue(sinkEvents.isEmpty());
            assertTrue(log.text().contains("Profile renamed to 'factry-r2', but this Ignition version cannot move it "
                    + "(needs 8.3.6 or newer): data still buffered in store and forward engine 'factry-r' "
                    + "(7 pending, 2 quarantined)"), log.text());
            assertTrue(log.events().stream().anyMatch(e -> e.getLevel() == Level.WARN
                    && e.getFormattedMessage().startsWith("Profile renamed to 'factry-r2'")), log.text());
        }
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

    /** Records the sink events; its engine holds 7 pending and 2 quarantined records. */
    private StoreAndForwardManager storeAndForwardManager() {
        return (StoreAndForwardManager) Proxy.newProxyInstance(StoreAndForwardManager.class.getClassLoader(),
                new Class<?>[]{StoreAndForwardManager.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "handleSinkLifecycleEvent" -> {
                        sinkEvents.add(args[0]);
                        yield null;
                    }
                    case "getEngineInformation" -> engines.stream()
                            .filter(r -> r.getResourceName().equalsIgnoreCase((String) args[0]))
                            .findFirst()
                            .map(r -> EngineInformation.newBuilder().engineId(r.getResourceName())
                                    .engineStatus(EngineStatus.fullyOperationStatus())
                                    .storeMetric(new Meter()).forwardMetric(new Meter())
                                    .totalPending(7).totalQuarantined(2).build());
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
                            ChangeOperation op = (ChangeOperation) o;
                            pushed.add(op);
                            Resource r = ChangeOperation.getResourceFromChange(op);
                            engines.removeIf(e -> e.getResourceName().equals(r.getResourceName()));
                            engines.add(r);
                        }
                        yield CompletableFuture.completedFuture(null);
                    }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
    }

    private static Resource engineResource(String name, StoreAndForwardEngineSettings settings) {
        ResourceBuilder builder = Resource.newBuilder();
        builder.setResourceCollectionName("core");
        builder.setResourcePath(StoreAndForwardEngineSettings.getType().childPath(name));
        builder.setApplicationScope("G");
        StoreAndForwardEngineSettings.getMeta().getCodec().encode(settings, builder);
        return builder.build();
    }

    private static StoreAndForwardEngineSettings settings(Resource resource) throws Exception {
        return StoreAndForwardEngineSettings.getMeta().getCodec().decode(resource).config();
    }
}
