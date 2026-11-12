package io.factry.historian.gateway;

import com.codahale.metrics.MetricRegistry;
import com.inductiveautomation.historian.common.model.TimeRange;
import com.inductiveautomation.historian.gateway.api.query.HistoricalNode;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A query for this Site's tag must resolve to this collector's measurement, also when another
 * Site has a measurement with the same name.
 */
class FactryQueryEngineSiteTest {

    private static final String NAME = "default/Pump1/Flow";
    // As a tag history binding sends it: [Factry Historian]default/Pump1/Flow
    private static final QualifiedPath PATH =
            QualifiedPath.newBuilder().setProvider("default").setTag(NAME).build();

    private static FactryQueryEngine engine(FactryGrpcClient client) {
        // The base engine only asks the context for a metric registry; fail loudly on anything else.
        MetricRegistry registry = new MetricRegistry();
        GatewayContext context = (GatewayContext) Proxy.newProxyInstance(
                GatewayContext.class.getClassLoader(), new Class<?>[]{GatewayContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getMetricRegistry")) {
                        return registry;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        MeasurementCache cache = new MeasurementCache();
        cache.refresh(client);
        return new FactryQueryEngine(context, "factry", new FactryHistorianSettings(), client,
                cache, new HistorianMetrics());
    }

    private static UUID nodeIdOf(String measurementUUID) {
        return new FactryRecord(measurementUUID, PATH, "number", Instant.EPOCH).nodeId();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void queryResolvesThisCollectorsMeasurement(boolean otherSiteLast) {
        MeasurementCacheTest.FakeFactry factry = new MeasurementCacheTest.FakeFactry();
        if (otherSiteLast) {
            factry.add(NAME, "own-uuid");
            factry.add(NAME, "other-site-uuid", "site-b-collector");
        } else {
            factry.add(NAME, "other-site-uuid", "site-b-collector");
            factry.add(NAME, "own-uuid");
        }
        FactryQueryEngine engine = engine(factry);

        Map<QualifiedPath, ? extends HistoricalNode> nodes = engine.queryForHistoricalNodes(
                Set.of(PATH), TimeRange.inclusive(Instant.EPOCH, Instant.now()));
        assertEquals(nodeIdOf("own-uuid"), nodes.get(PATH).nodeId());
        assertEquals(nodeIdOf("own-uuid"), engine.lookupNode(PATH).orElseThrow().nodeId());
    }
}
