package io.factry.historian.gateway;

import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.history.AggregationMode;
import io.factry.historian.gateway.IgnitionHistoryQuery.Dataset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Failed reads through Ignition's own query path. A failed read must end the dataset with an
 * error (what the Power Chart and clients show), not come back as an empty or Bad_NotFound result
 * that looks like a quiet or unknown tag.
 */
class ReadErrorTest {

    private static final long START = 1_794_268_800_000L; // 2026-11-10T00:00:00Z
    private static final long FIVE_SECONDS = 5_000L;
    private static final long FIVE_MINUTES = 300_000L;

    private FakeFactry factry;
    private FactryGrpcClient client;
    private FactryQueryEngine engine;

    @BeforeEach
    void setUp() throws Exception {
        factry = new FakeFactry();
        client = factry.client();
        String a = factry.addMeasurement("default/a", "number");
        String b = factry.addMeasurement("default/b", "number");
        for (int s = 0; s < 600; s++) {
            factry.addPoint(a, START + s * 1000L, s);
            factry.addPoint(b, START + s * 1000L, s);
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        client.shutdown();
        factry.close();
    }

    private void startEngine(boolean measurementsKnown) {
        FactryHistorianSettings settings = new FactryHistorianSettings();
        settings.setDelimiter("/");
        MeasurementCache cache = new MeasurementCache();
        if (measurementsKnown) {
            cache.refresh(client);
        }
        engine = new FactryQueryEngine(FakeFactry.gatewayContext(), "Factry", settings, client, cache,
                new HistorianMetrics());
    }

    private Dataset raw(String... tags) throws Exception {
        return IgnitionHistoryQuery.wide(engine, START, START + FIVE_SECONDS, -1, null, tags);
    }

    private Dataset average(String... tags) throws Exception {
        return IgnitionHistoryQuery.wide(engine, START, START + FIVE_MINUTES, 5, AggregationMode.Average, tags);
    }

    private static void assertReadFailed(Dataset ds, String detail) {
        assertNotNull(ds.error, () -> "expected an error, got:\n" + ds);
        assertTrue(ds.error.getMessage().contains("Factry Historian read failed"), ds.error::getMessage);
        assertTrue(ds.error.getMessage().contains(detail), ds.error::getMessage);
    }

    @Test
    void rawReadWithFactryUnreachableIsAnError() throws Exception {
        startEngine(true);
        factry.close();

        assertReadFailed(raw("a", "b"), "UNAVAILABLE");
    }

    @Test
    void aggregatedReadWithFactryUnreachableIsAnError() throws Exception {
        startEngine(true);
        factry.close();

        assertReadFailed(average("a", "b"), "UNAVAILABLE");
    }

    @Test
    void factryUnreachableBeforeTheFirstLookupIsAnErrorNotNotFound() throws Exception {
        // Nothing cached yet: resolving the tags needs Factry, and that fails.
        startEngine(false);
        factry.close();

        assertReadFailed(raw("a", "b"), "UNAVAILABLE");
        assertReadFailed(average("a", "b"), "UNAVAILABLE");
    }

    @Test
    void aggregatedReadThatFactryRejectsForOnePenIsAnError() throws Exception {
        startEngine(true);
        factry.failingMeasurements.add("uuid-2");

        assertReadFailed(average("a", "b"), "INTERNAL");
    }

    @Test
    void unknownTagIsStillNotFoundAndNotAnError() throws Exception {
        startEngine(true);

        Dataset ds = raw("a", "c");

        assertNull(ds.error);
        assertEquals(6, ds.rows.size(), ds::toString);
        for (QualityCode[] q : ds.qualities) {
            assertTrue(q[1].isGood(), ds::toString);
            assertTrue(q[2].is(QualityCode.Bad_NotFound), ds::toString);
        }
    }

    @Test
    void rangeWithoutDataIsEmptyAndNotAnError() throws Exception {
        startEngine(true);

        Dataset ds = IgnitionHistoryQuery.wide(engine, START - 60_000L, START - 1L, -1, null, "a", "b");

        assertNull(ds.error);
        assertEquals(0, ds.rows.size(), ds::toString);
    }
}
