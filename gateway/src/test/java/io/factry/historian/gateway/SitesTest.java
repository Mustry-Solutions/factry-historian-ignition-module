package io.factry.historian.gateway;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.inductiveautomation.historian.gateway.api.query.browsing.RootBrowsePublisher;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.browsing.Result;
import io.factry.historian.proto.GetMeasurementsByFilterRequest;
import io.factry.historian.proto.QueryTimeseriesRequest;
import io.factry.historian.proto.QueryTimeseriesResponse;
import io.factry.historian.proto.Series;
import io.factry.historian.proto.SeriesPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Several Sites in one Factry organisation: each Site's gateway has its own collector writing to
 * its own time-series database, and the same tag has the same measurement name at every Site
 * (#23, #52). A tag's own path reads only its own Site. Other Sites stay browsable and readable,
 * under their own collector's folder, by a node id that names their database.
 */
class SitesTest {

    private static final long T0 = 1_794_480_000_000L;
    private static final String SITE_B = "collector-b";
    private static final String OPC = "opc-collector";

    private final FakeFactry factry = new FakeFactry();
    private final MeasurementCache cache = new MeasurementCache(MeasurementCacheTest.NO_WAIT);
    private FactryQueryEngine engine;

    @BeforeEach
    void start() {
        factry.databases.put("db-b", "site_b");
        // Site A, this gateway: its own tag, and an OPC UA collector writing to the same database.
        factry.add("default/Pump1/Flow", "a-flow");
        factry.add("default/Opc/Temp", "opc-temp", OPC, "Active");
        // Site B: the same tag, and one only Site B has.
        factry.database = "db-b";
        factry.add("default/Pump1/Flow", "b-flow", SITE_B, "Active");
        factry.add("default/OnlyB/Flow", "b-only", SITE_B, "Active");
        factry.database = FakeFactry.DB;
        cache.refresh(factry);
        FactryHistorianSettings settings = new FactryHistorianSettings();
        settings.setDelimiter("/");
        engine = new FactryQueryEngine(FakeFactryServer.gatewayContext(), "Factry", settings, factry, cache,
                new HistorianMetrics());
        factry.respond = this::answer;
    }

    @AfterEach
    void stop() {
        factry.shutdown();
    }

    /** Every read answers one point per measurement it asks for, with that measurement's value. */
    private static final Map<String, Double> VALUES = Map.of(
            "a-flow", 1.0, "opc-temp", 5.0, "b-flow", 2.0, "b-only", 22.0, "b-new", 33.0, "x-only", 9.0);

    private QueryTimeseriesResponse answer(QueryTimeseriesRequest request) {
        QueryTimeseriesResponse.Builder reply = QueryTimeseriesResponse.newBuilder();
        for (String uuid : request.getMeasurementUUIDsList()) {
            reply.addSeries(Series.newBuilder().setMeasurementUUID(uuid)
                    .setTags(Struct.newBuilder().putFields("status", Value.newBuilder().setStringValue("Good").build()))
                    .addDataPoints(SeriesPoint.newBuilder().setTimestamp(T0 - 30_000)
                            .setValue(Value.newBuilder().setNumberValue(VALUES.getOrDefault(uuid, -1.0)))));
        }
        return reply.build();
    }

    /** The value a raw read gives a path, or null when it gives none. */
    private Object read(String path) throws Exception {
        IgnitionHistoryQuery.Dataset ds = IgnitionHistoryQuery.rawPaths(engine, T0 - 60_000, T0, path);
        return ds.rows.stream().map(row -> row[1]).filter(v -> v != null).findFirst().orElse(null);
    }

    private List<String> uuidsRead() {
        return factry.queries.stream().flatMap(q -> q.getMeasurementUUIDsList().stream()).distinct().toList();
    }

    private Map<String, String> browse(String path) {
        QualifiedPath root = QualifiedPath.parseSafe(path);
        RootBrowsePublisher publisher = new RootBrowsePublisher(root);
        engine.doBrowse(root, null, publisher);
        return publisher.getResults().getResults().stream().collect(Collectors.toMap(
                (Result r) -> r.getPath().getLastPathComponent(),
                (Result r) -> String.valueOf(r.getPath().getPathComponent("tag"))));
    }

    @Test
    void aTagReadsItsOwnSitesMeasurement() throws Exception {
        assertEquals(1.0, read("sys:siteA:/prov:default:/tag:Pump1/Flow"));
        assertEquals(List.of("a-flow"), uuidsRead());
    }

    @Test
    void aTagOnlyAnotherSiteHasReadsNothing() throws Exception {
        assertNull(read("sys:siteA:/prov:default:/tag:OnlyB/Flow"), "not Site B's data marked Good");
        assertEquals(List.of(), uuidsRead());
    }

    @Test
    void aNewTagIsNotLookedUpInAnotherSite() throws Exception {
        factry.database = "db-b";
        factry.add("default/New/Flow", "b-new", SITE_B, "Active");

        assertNull(read("sys:siteA:/prov:default:/tag:New/Flow"));
        assertEquals(List.of(), uuidsRead());
        GetMeasurementsByFilterRequest lookup = factry.filterRequests.get(factry.filterRequests.size() - 1);
        assertEquals(List.of(FakeFactry.DB), lookup.getDatabaseUUIDsList(), "the lookup is scoped to the own Site");
    }

    @Test
    void anotherCollectorAtTheSameSiteReadsByItsPath() throws Exception {
        assertEquals(5.0, read("sys:siteA:/prov:default:/tag:Opc/Temp"));
    }

    @Test
    void everyBrowsedTagReadsTheMeasurementOfItsCollectorFolder() throws Exception {
        Map<String, String> own = browse(
                "histprov:Factry:/folder:Measurements:/folder:collector:/folder:default:/folder:Pump1");
        Map<String, String> siteB = browse(
                "histprov:Factry:/folder:Measurements:/folder:collector-b:/folder:default:/folder:Pump1");

        assertEquals(Map.of("Flow", "default/Pump1/Flow"), own);
        assertEquals(Map.of("Flow", "[site_b]default/Pump1/Flow"), siteB);
        assertEquals(1.0, read("histprov:Factry:/tag:" + own.get("Flow")));
        assertEquals(2.0, read("histprov:Factry:/tag:" + siteB.get("Flow")), "Site B's value under Site B's folder");
    }

    @Test
    void anotherSitesNewMeasurementReadsAtOnceByItsDatabaseAndName() throws Exception {
        factry.database = "db-b";
        factry.add("default/New/Flow", "b-new", SITE_B, "Active");

        assertEquals(33.0, read("histprov:Factry:/tag:[site_b]default/New/Flow"));
        GetMeasurementsByFilterRequest lookup = factry.filterRequests.get(factry.filterRequests.size() - 1);
        assertEquals(List.of("db-b"), lookup.getDatabaseUUIDsList());
    }

    @Test
    void withOneDatabaseEveryCollectorsTagReadsByItsPathBeforeTheFirstWrite() throws Exception {
        FakeFactry single = new FakeFactry();
        try {
            single.add("default/Line/Speed", "x-only", OPC, "Active");
            MeasurementCache fresh = new MeasurementCache(MeasurementCacheTest.NO_WAIT);
            fresh.refresh(single);
            single.respond = this::answer;
            FactryQueryEngine singleEngine = new FactryQueryEngine(FakeFactryServer.gatewayContext(), "Factry",
                    new FactryHistorianSettings(), single, fresh, new HistorianMetrics());

            IgnitionHistoryQuery.Dataset ds = IgnitionHistoryQuery.wide(singleEngine, T0 - 60_000, T0, -1, null,
                    "Line/Speed");

            assertEquals(9.0, ds.rows.get(0)[1], ds.toString());
        } finally {
            single.shutdown();
        }
    }

    @Test
    void aMeasurementWithoutACollectorDoesNotMakeItsDatabaseTheOwnSite() throws Exception {
        factry.database = "db-b";
        factry.add("default/Calc/Total", "x-only", "", "Active");
        cache.refresh(factry);

        assertNull(read("sys:siteA:/prov:default:/tag:OnlyB/Flow"));
        assertEquals(9.0, read("histprov:Factry:/tag:[site_b]default/Calc/Total"));
    }

    @Test
    void theDelimiterLeavesTheDatabaseNameAlone() throws Exception {
        factry.databases.put("db-b", "site.b");
        cache.refresh(factry);
        FactryHistorianSettings dots = new FactryHistorianSettings();
        dots.setDelimiter(".");
        engine.updateSettings(dots);

        Map<String, String> siteB = browse("histprov:Factry:/folder:Measurements:/folder:collector-b");

        // Flat: the name's '/' are escaped, the database name keeps its '.'.
        String id = "[site.b]" + TagPathUtil.toBrowseName("default/Pump1/Flow", ".");
        assertTrue(siteB.containsValue(id), siteB.toString());
        assertEquals(2.0, read("histprov:Factry:/tag:" + id));
    }

    @Test
    void aBrowsedTagOfAnotherSiteIsNotShadowedByTheOwnSitesName() {
        // The own Site's tag and Site B's have the same name; both are listed, each once.
        Map<String, String> siteB = browse(
                "histprov:Factry:/folder:Measurements:/folder:collector-b:/folder:default:/folder:OnlyB");
        assertEquals(Map.of("Flow", "[site_b]default/OnlyB/Flow"), siteB);
        assertTrue(browse("histprov:Factry:/folder:Measurements").keySet()
                .containsAll(List.of("collector", "collector-b", "opc-collector")));
    }
}
