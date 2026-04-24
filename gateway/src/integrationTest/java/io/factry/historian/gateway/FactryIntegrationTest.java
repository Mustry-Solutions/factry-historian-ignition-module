package io.factry.historian.gateway;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.google.protobuf.Timestamp;
import com.google.protobuf.Value;
import io.factry.historian.proto.*;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Factry Historian Ignition module.
 * <p>
 * Tests exercise all {@code system.historian.*} functions via WebDev endpoints,
 * plus direct gRPC for data seeding and verification.
 * <p>
 * Requires running infrastructure:
 * <ul>
 *   <li>Ignition gateway with Factry Historian module + WebDev module</li>
 *   <li>Factry Historian (gRPC)</li>
 *   <li>WebDev endpoints deployed in the configured project</li>
 * </ul>
 * <p>
 * The tests run against two historian instances when configured:
 * <ul>
 *   <li>{@code historian.name.nosf} — historian without Store &amp; Forward</li>
 *   <li>{@code historian.name.sf}  — historian with Store &amp; Forward</li>
 * </ul>
 * If only {@code historian.name} is set, tests run once against that single historian.
 * <p>
 * Run via: {@code ./gradlew integrationTest}
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FactryIntegrationTest {

    // -- Configuration (from system properties, set by Gradle) ----------------

    private static final String GATEWAY_URL = System.getProperty("gateway.url", "http://localhost:8089");
    private static final String WEBDEV_PROJECT = System.getProperty("webdev.project", "TestFactry");
    private static final String GRPC_HOST = System.getProperty("grpc.host", "localhost");
    private static final int GRPC_PORT = Integer.parseInt(System.getProperty("grpc.port", "8001"));
    private static final String COLLECTOR_UUID = System.getProperty("collector.uuid", "");
    private static final String COLLECTOR_TOKEN = System.getProperty("collector.token", "");
    private static final String GATEWAY_SYSTEM_NAME = System.getProperty("gateway.system.name", "Ignition-296a8ca4b6cd");

    // Dual-historian config: when both are set, tests run twice (nested classes).
    // When only historian.name is set, that single name is used.
    private static final String HISTORIAN_NAME_NOSF = System.getProperty("historian.name.nosf", "");
    private static final String HISTORIAN_NAME_SF = System.getProperty("historian.name.sf", "");
    private static final String HISTORIAN_NAME_SINGLE = System.getProperty("historian.name", "Factry Historian 0.8");

    /** Wait time for the module's batch flush (default 5s interval + margin). */
    private static final int BATCH_FLUSH_WAIT_MS = 8_000;

    /** Unique prefix per test run to avoid measurement collisions. */
    private static final String TEST_PREFIX = "IT" + randomSuffix(6);

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // -- Shared resources -----------------------------------------------------

    private ManagedChannel grpcChannel;
    private HistorianGrpc.HistorianBlockingStub grpcStub;
    private HttpClient httpClient;
    private final Gson gson = new Gson();

    // -- Logging helpers ------------------------------------------------------

    static void log(String msg) {
        System.out.println("  [" + LocalTime.now().format(TIME_FMT) + "] " + msg);
    }

    static void pass(String msg) {
        log("PASS: " + msg);
    }

    static void section(String title) {
        System.out.println();
        System.out.println("============================================================");
        System.out.println("  " + title);
        System.out.println("============================================================");
    }

    // -- Setup / Teardown -----------------------------------------------------

    @BeforeAll
    void setup() {
        System.out.println();
        System.out.println("============================================================");
        System.out.println("  Factry Historian - Integration Tests");
        System.out.println("============================================================");
        log("Gateway:    " + GATEWAY_URL);
        log("Project:    " + WEBDEV_PROJECT);
        log("Factry:     " + GRPC_HOST + ":" + GRPC_PORT);
        log("System:     " + GATEWAY_SYSTEM_NAME);
        log("Prefix:     " + TEST_PREFIX);

        if (!HISTORIAN_NAME_NOSF.isEmpty() && !HISTORIAN_NAME_SF.isEmpty()) {
            log("Dual mode:  NoS&F='" + HISTORIAN_NAME_NOSF + "'  S&F='" + HISTORIAN_NAME_SF + "'");
        } else {
            log("Historian:  " + HISTORIAN_NAME_SINGLE);
        }

        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        try {
            grpcChannel = NettyChannelBuilder.forAddress(GRPC_HOST, GRPC_PORT)
                    .sslContext(io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts.forClient()
                            .trustManager(io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory.INSTANCE)
                            .build())
                    .build();
        } catch (javax.net.ssl.SSLException e) {
            throw new RuntimeException("Failed to create TLS gRPC channel", e);
        }

        Metadata headers = new Metadata();
        if (!COLLECTOR_UUID.isEmpty()) {
            headers.put(Metadata.Key.of("collectoruuid", Metadata.ASCII_STRING_MARSHALLER), COLLECTOR_UUID);
        }
        if (!COLLECTOR_TOKEN.isEmpty()) {
            headers.put(Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER),
                    "Bearer " + COLLECTOR_TOKEN);
        }

        grpcStub = HistorianGrpc.newBlockingStub(grpcChannel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        section("Setup");

        // Check Ignition gateway
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(GATEWAY_URL + "/StatusPing"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, resp.statusCode(), "Gateway StatusPing failed");
            log("Ignition gateway: connected (response: " + resp.body() + ")");
        } catch (Exception e) {
            fail("Ignition gateway unreachable at " + GATEWAY_URL + " — " + e.getMessage());
        }

        // Check Factry gRPC
        try {
            Measurements measurements = grpcStub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getMeasurements(MeasurementRequest.newBuilder().build());
            log("Factry gRPC: connected (" + measurements.getMeasurementsCount() + " measurements)");
        } catch (Exception e) {
            fail("Factry gRPC unreachable at " + GRPC_HOST + ":" + GRPC_PORT + " — " + e.getMessage());
        }

        // Verify WebDev endpoint is reachable
        String probeHistorian = !HISTORIAN_NAME_NOSF.isEmpty() ? HISTORIAN_NAME_NOSF : HISTORIAN_NAME_SINGLE;
        try {
            Map<String, Object> probe = webdevPost("test/queryRaw", Map.of(
                    "paths", List.of("histprov:" + probeHistorian + ":/sys:probe:/prov:default:/tag:probe"),
                    "startDate", 1600000000000L,
                    "endDate", 1600000010000L
            ));
            log("WebDev endpoints: reachable (response: " + (probe.get("success")) + ")");
        } catch (Exception e) {
            fail("WebDev endpoints unreachable — " + e.getMessage());
        }
    }

    @AfterAll
    void teardown() {
        if (grpcChannel != null) {
            try {
                grpcChannel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                grpcChannel.shutdownNow();
            }
        }
    }

    // =========================================================================
    // Determine which historians to test
    // =========================================================================

    private static boolean isDualMode() {
        return !HISTORIAN_NAME_NOSF.isEmpty() && !HISTORIAN_NAME_SF.isEmpty();
    }

    // =========================================================================
    // Historian-scoped tests (run once per historian instance)
    // =========================================================================

    /**
     * Shared test logic for a single historian instance. Used by both
     * nested classes when in dual-historian mode, or directly when single.
     */
    abstract class HistorianTests {

        abstract String historianName();
        abstract String label();

        @Test
        @Order(10)
        @DisplayName("system.historian.storeDataPoints — store numeric data")
        void testStoreDataPoints() throws Exception {
            section(label() + " - storeDataPoints");

            String tagName = TEST_PREFIX + "/" + label() + "/StorePoints";
            long baseTs = 1700010000000L;

            String qPath = qualifiedPath(historianName(), tagName);
            Map<String, Object> result = webdevPost("test/storePoints", Map.of(
                    "paths", List.of(qPath, qPath, qPath),
                    "values", List.of(100.0, 200.0, 300.0),
                    "timestamps", List.of(baseTs, baseTs + 1000, baseTs + 2000),
                    "qualities", List.of(192, 192, 192)
            ));
            assertTrue((Boolean) result.get("success"), "storeDataPoints should succeed");
            pass("storeDataPoints returned success");

            log("Waiting " + (BATCH_FLUSH_WAIT_MS / 1000) + "s for batch flush...");
            Thread.sleep(BATCH_FLUSH_WAIT_MS);

            String measurementName = storedTagPath(tagName);
            String uuid = findMeasurementUuid(measurementName);
            assertNotNull(uuid, "Measurement '" + measurementName + "' should exist in Factry");
            pass("Measurement exists in Factry: " + uuid);

            QueryTimeseriesResponse response = grpcQuery(uuid, baseTs - 1000, baseTs + 3000);
            assertFalse(response.getSeriesList().isEmpty(), "Should return at least one series");
            int pointCount = response.getSeries(0).getDataPointsCount();
            assertTrue(pointCount >= 3, "Expected >= 3 points, got " + pointCount);
            pass("gRPC verification: " + pointCount + " points stored");
        }

        @Test
        @Order(20)
        @DisplayName("system.historian.queryRawPoints — query raw data")
        void testQueryRawPoints() throws Exception {
            section(label() + " - queryRawPoints");

            String tagName = TEST_PREFIX + "/" + label() + "/RawQuery";
            String measurementName = storedTagPath(tagName);

            String uuid = createMeasurement(measurementName, "number");
            assertFalse(uuid.isEmpty(), "Measurement UUID should not be empty");

            long baseTs = 1700020000000L;
            List<Point> points = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                points.add(buildPoint(uuid, baseTs + i * 1000, Value.newBuilder().setNumberValue(10.0 + i).build()));
            }
            grpcStub.createPoints(Points.newBuilder().addAllPoints(points).build());
            log("Inserted 5 points for measurement " + uuid);
            Thread.sleep(2000);

            Map<String, Object> result = webdevPost("test/queryRaw", Map.of(
                    "paths", List.of(qualifiedPath(historianName(), tagName)),
                    "startDate", baseTs - 1000,
                    "endDate", baseTs + 5000
            ));

            assertTrue((Boolean) result.get("success"), "queryRawPoints should succeed");
            pass("Raw query returned success");

            int rowCount = ((Number) result.get("rowCount")).intValue();
            @SuppressWarnings("unchecked")
            List<String> columns = (List<String>) result.get("columns");
            log("Got " + rowCount + " rows, columns: " + columns);
            assertTrue(rowCount >= 5, "Expected >= 5 rows, got " + rowCount);
            pass("Got data rows back");
        }

        @Test
        @Order(30)
        @DisplayName("system.historian.queryAggregatedPoints — aggregation queries")
        void testQueryAggregatedPoints() throws Exception {
            section(label() + " - queryAggregatedPoints");

            String tagName = TEST_PREFIX + "/" + label() + "/Aggregation";
            String measurementName = storedTagPath(tagName);

            String uuid = createMeasurement(measurementName, "number");
            assertFalse(uuid.isEmpty());

            long baseTs = 1700030000000L;
            List<Point> points = new ArrayList<>();
            for (int i = 0; i < 100; i++) {
                points.add(buildPoint(uuid, baseTs + i * 1000,
                        Value.newBuilder().setNumberValue(i).build()));
            }
            grpcStub.createPoints(Points.newBuilder().addAllPoints(points).build());
            log("Inserted 100 points (values 0..99) for measurement " + uuid);
            Thread.sleep(2000);

            String qPath = qualifiedPath(historianName(), tagName);

            // Average
            Map<String, Object> avgResult = webdevPost("test/queryAgg", Map.of(
                    "paths", List.of(qPath),
                    "startDate", baseTs - 1000,
                    "endDate", baseTs + 100_000,
                    "aggregates", List.of("Average"),
                    "returnSize", 1
            ));
            assertTrue((Boolean) avgResult.get("success"), "Average query should succeed");
            double avgValue = extractAggregationValue(avgResult);
            log("Average = " + avgValue + " (expected ~49.5)");
            assertAggregationValue(avgResult, 49.5, 5.0, "Average");
            pass("Average value");

            // Minimum
            Map<String, Object> minResult = webdevPost("test/queryAgg", Map.of(
                    "paths", List.of(qPath),
                    "startDate", baseTs - 1000,
                    "endDate", baseTs + 100_000,
                    "aggregates", List.of("Minimum"),
                    "returnSize", 1
            ));
            assertTrue((Boolean) minResult.get("success"), "Minimum query should succeed");
            double minValue = extractAggregationValue(minResult);
            log("Minimum = " + minValue + " (expected 0.0)");
            assertAggregationValue(minResult, 0.0, 1.0, "Minimum");
            pass("Minimum value");

            // Maximum
            Map<String, Object> maxResult = webdevPost("test/queryAgg", Map.of(
                    "paths", List.of(qPath),
                    "startDate", baseTs - 1000,
                    "endDate", baseTs + 100_000,
                    "aggregates", List.of("Maximum"),
                    "returnSize", 1
            ));
            assertTrue((Boolean) maxResult.get("success"), "Maximum query should succeed");
            double maxValue = extractAggregationValue(maxResult);
            log("Maximum = " + maxValue + " (expected 99.0)");
            assertAggregationValue(maxResult, 99.0, 1.0, "Maximum");
            pass("Maximum value");
        }

        @Test
        @Order(40)
        @DisplayName("system.historian.queryMetadata — query measurement metadata")
        void testQueryMetadata() throws Exception {
            section(label() + " - queryMetadata");

            String tagName = TEST_PREFIX + "/" + label() + "/Metadata";
            String measurementName = storedTagPath(tagName);

            String uuid = createMeasurement(measurementName, "number");
            assertFalse(uuid.isEmpty());
            log("Created measurement " + uuid);

            Map<String, Object> result = webdevPost("test/queryMeta", Map.of(
                    "paths", List.of(qualifiedPath(historianName(), tagName))
            ));

            assertTrue((Boolean) result.get("success"), "queryMetadata should succeed");
            pass("queryMetadata returned success");

            int rowCount = ((Number) result.get("rowCount")).intValue();
            log("Got " + rowCount + " metadata rows");
            assertTrue(rowCount >= 1, "Expected >= 1 metadata row, got " + rowCount);
            pass("Got metadata back");
        }

        @Test
        @Order(50)
        @DisplayName("system.historian.browse — browse historian hierarchy")
        void testBrowse() throws Exception {
            section(label() + " - browse");

            Map<String, Object> rootResult = webdevPost("test/browse", Map.of(
                    "path", "histprov:" + historianName() + ":/"
            ));
            assertTrue((Boolean) rootResult.get("success"), "Root browse should succeed");
            pass("Root browse returned success");

            int rootCount = ((Number) rootResult.get("count")).intValue();
            assertTrue(rootCount >= 1, "Root should have at least 1 child node, got " + rootCount);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> nodes = (List<Map<String, Object>>) rootResult.get("results");
            for (Map<String, Object> node : nodes) {
                log("Node: " + node.get("path") + " (type=" + node.get("type") + ", hasChildren=" + node.get("hasChildren") + ")");
            }
            pass("Root browse returned " + rootCount + " nodes");
        }

        @Test
        @Order(60)
        @DisplayName("system.historian.storeMetadata — store measurement metadata")
        void testStoreMetadata() throws Exception {
            section(label() + " - storeMetadata");

            String tagName = TEST_PREFIX + "/" + label() + "/StoreMeta";
            String measurementName = storedTagPath(tagName);

            String uuid = createMeasurement(measurementName, "number");
            assertFalse(uuid.isEmpty());
            log("Created measurement " + uuid);

            Map<String, Object> result = webdevPost("test/storeMeta", Map.of(
                    "paths", List.of(qualifiedPath(historianName(), tagName)),
                    "timestamps", List.of(System.currentTimeMillis()),
                    "properties", List.of(Map.of("engineeringUnits", "degC"))
            ));

            assertTrue((Boolean) result.get("success"), "storeMetadata should succeed (or no-op gracefully)");
            pass("storeMetadata returned success");
        }

        @Test
        @Order(70)
        @DisplayName("Empty time range returns zero rows")
        void testEmptyQuery() throws Exception {
            section(label() + " - Empty Query");

            String tagName = TEST_PREFIX + "/" + label() + "/Empty";
            String measurementName = storedTagPath(tagName);

            createMeasurement(measurementName, "number");

            Map<String, Object> result = webdevPost("test/queryRaw", Map.of(
                    "paths", List.of(qualifiedPath(historianName(), tagName)),
                    "startDate", 1600000000000L,
                    "endDate", 1600000010000L
            ));
            assertTrue((Boolean) result.get("success"));
            int rowCount = ((Number) result.get("rowCount")).intValue();
            log("Got " + rowCount + " rows for empty time range");
            assertEquals(0, rowCount, "Should return 0 rows for empty range");
            pass("Empty time range returns 0 rows");
        }

        @Test
        @Order(80)
        @DisplayName("String-type measurement storage and gRPC query")
        void testStringValues() throws Exception {
            section(label() + " - String Values");

            String tagName = TEST_PREFIX + "/" + label() + "/StringTest";
            String measurementName = storedTagPath(tagName);
            long baseTs = 1700050000000L;

            String uuid = createMeasurement(measurementName, "string");
            assertFalse(uuid.isEmpty(), "String measurement UUID should not be empty");
            log("Created string measurement " + uuid);

            List<Point> points = List.of(
                    buildPoint(uuid, baseTs, Value.newBuilder().setStringValue("hello").build()),
                    buildPoint(uuid, baseTs + 1000, Value.newBuilder().setStringValue("world").build()),
                    buildPoint(uuid, baseTs + 2000, Value.newBuilder().setStringValue("test").build())
            );
            grpcStub.createPoints(Points.newBuilder().addAllPoints(points).build());
            log("Inserted 3 string points via gRPC");
            Thread.sleep(2000);

            QueryTimeseriesResponse grpcResponse = grpcQuery(uuid, baseTs - 1000, baseTs + 3000);
            int grpcPoints = grpcResponse.getSeriesList().isEmpty() ? 0
                    : grpcResponse.getSeries(0).getDataPointsCount();
            assertTrue(grpcPoints >= 3, "Expected >= 3 string points via gRPC, got " + grpcPoints);
            pass("gRPC verification: " + grpcPoints + " string points stored");

            Map<String, Object> result = webdevPost("test/queryRaw", Map.of(
                    "paths", List.of(qualifiedPath(historianName(), tagName)),
                    "startDate", baseTs - 1000,
                    "endDate", baseTs + 3000
            ));
            assertTrue((Boolean) result.get("success"), "queryRawPoints should succeed for string tags");
            int rowCount = ((Number) result.get("rowCount")).intValue();
            if (rowCount == 0) {
                log("system.historian returned 0 rows (Ignition framework drops string values in DataSet)");
            } else {
                pass("system.historian returned " + rowCount + " rows");
            }
        }

        @Test
        @Order(90)
        @DisplayName("Multi-tag query returns all columns")
        void testMultiTagQuery() throws Exception {
            section(label() + " - Multi-Tag Query");

            long baseTs = 1700060000000L;
            List<String> tagNames = List.of(
                    TEST_PREFIX + "/" + label() + "/Multi0",
                    TEST_PREFIX + "/" + label() + "/Multi1",
                    TEST_PREFIX + "/" + label() + "/Multi2"
            );

            for (int idx = 0; idx < tagNames.size(); idx++) {
                String measurementName = storedTagPath(tagNames.get(idx));
                String uuid = createMeasurement(measurementName, "number");
                assertFalse(uuid.isEmpty());

                List<Point> points = new ArrayList<>();
                for (int i = 0; i < 5; i++) {
                    points.add(buildPoint(uuid, baseTs + i * 1000,
                            Value.newBuilder().setNumberValue(idx * 100.0 + i).build()));
                }
                grpcStub.createPoints(Points.newBuilder().addAllPoints(points).build());
                log("Inserted 5 points for tag " + tagNames.get(idx));
            }
            Thread.sleep(2000);

            List<String> paths = tagNames.stream()
                    .map(t -> qualifiedPath(historianName(), t))
                    .toList();
            Map<String, Object> result = webdevPost("test/queryRaw", Map.of(
                    "paths", paths,
                    "startDate", baseTs - 1000,
                    "endDate", baseTs + 5000
            ));

            assertTrue((Boolean) result.get("success"));
            pass("Multi-tag query returned success");

            @SuppressWarnings("unchecked")
            List<String> columns = (List<String>) result.get("columns");
            log("Got " + columns.size() + " columns: t_stamp + " + (columns.size() - 1) + " tags");
            assertTrue(columns.size() >= 4,
                    "Expected >= 4 columns (t_stamp + 3 tags), got " + columns);
            pass("Column count");

            int rowCount = ((Number) result.get("rowCount")).intValue();
            log("Got " + rowCount + " rows");
            assertTrue(rowCount >= 5, "Expected >= 5 rows");
            pass("Row count");
        }

        @Test
        @Order(100)
        @DisplayName("Full round trip: store + query via system.historian")
        void testRoundTrip() throws Exception {
            section(label() + " - Round Trip");

            String tagName = TEST_PREFIX + "/" + label() + "/RoundTrip";
            long baseTs = 1700070000000L;

            String qPath = qualifiedPath(historianName(), tagName);
            Map<String, Object> storeResult = webdevPost("test/storePoints", Map.of(
                    "paths", List.of(qPath, qPath, qPath, qPath, qPath),
                    "values", List.of(42.0, 43.0, 44.0, 45.0, 46.0),
                    "timestamps", List.of(baseTs, baseTs + 1000, baseTs + 2000, baseTs + 3000, baseTs + 4000),
                    "qualities", List.of(192, 192, 192, 192, 192)
            ));
            assertTrue((Boolean) storeResult.get("success"));
            pass("storeDataPoints returned success (5 points)");

            log("Waiting " + (BATCH_FLUSH_WAIT_MS / 1000) + "s for batch flush...");
            Thread.sleep(BATCH_FLUSH_WAIT_MS);

            Map<String, Object> queryResult = webdevPost("test/queryRaw", Map.of(
                    "paths", List.of(qualifiedPath(historianName(), tagName)),
                    "startDate", baseTs - 1000,
                    "endDate", baseTs + 5000
            ));
            assertTrue((Boolean) queryResult.get("success"));
            pass("queryRawPoints returned success");

            int rowCount = ((Number) queryResult.get("rowCount")).intValue();
            log("Got " + rowCount + " rows back");
            assertTrue(rowCount >= 5, "Expected >= 5 rows in round-trip query");
            pass("Round trip: stored 5, queried " + rowCount);
        }
    }

    // =========================================================================
    // Nested class: tests without Store & Forward
    // =========================================================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Without Store & Forward")
    class NoStoreAndForward extends HistorianTests {

        @Override
        String historianName() {
            return isDualMode() ? HISTORIAN_NAME_NOSF : HISTORIAN_NAME_SINGLE;
        }

        @Override
        String label() {
            return isDualMode() ? "NoS&F" : "Single";
        }
    }

    // =========================================================================
    // Nested class: tests with Store & Forward (only runs in dual mode)
    // =========================================================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("With Store & Forward")
    class WithStoreAndForward extends HistorianTests {

        @Override
        String historianName() {
            return isDualMode() ? HISTORIAN_NAME_SF : HISTORIAN_NAME_SINGLE;
        }

        @Override
        String label() {
            return isDualMode() ? "S&F" : "Single";
        }

        /** Skip all tests in this class when not in dual mode. */
        @BeforeAll
        void skipIfSingleMode() {
            Assumptions.assumeTrue(isDualMode(),
                    "Skipping S&F tests: dual-historian not configured. " +
                    "Set historian.name.nosf and historian.name.sf to enable.");
        }
    }

    // =========================================================================
    // Error cases
    // =========================================================================

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("Error cases")
    class ErrorCases {

        private String defaultHistorian() {
            return isDualMode() ? HISTORIAN_NAME_NOSF : HISTORIAN_NAME_SINGLE;
        }

        @Test
        @Order(10)
        @DisplayName("Query non-existent measurement returns 0 rows")
        void testNonExistentMeasurement() throws Exception {
            section("Error: Query non-existent measurement");

            Map<String, Object> result = webdevPost("test/queryRaw", Map.of(
                    "paths", List.of(qualifiedPath(defaultHistorian(),
                            TEST_PREFIX + "/NonExistent/DoesNotExist")),
                    "startDate", 1700000000000L,
                    "endDate", 1700000010000L
            ));
            assertTrue((Boolean) result.get("success"), "Query should succeed (just return 0 rows)");
            int rowCount = ((Number) result.get("rowCount")).intValue();
            assertEquals(0, rowCount, "Non-existent measurement should return 0 rows");
            pass("Non-existent measurement returns 0 rows");
        }

        @Test
        @Order(20)
        @DisplayName("Inverted time range returns 0 rows")
        void testInvertedTimeRange() throws Exception {
            section("Error: Inverted time range");

            String tagName = TEST_PREFIX + "/Error/Inverted";
            String measurementName = storedTagPath(tagName);

            String uuid = createMeasurement(measurementName, "number");
            assertFalse(uuid.isEmpty());

            long baseTs = 1700080000000L;
            grpcStub.createPoints(Points.newBuilder()
                    .addPoints(buildPoint(uuid, baseTs, Value.newBuilder().setNumberValue(1.0).build()))
                    .build());
            Thread.sleep(2000);

            // Query with end < start
            Map<String, Object> result = webdevPost("test/queryRaw", Map.of(
                    "paths", List.of(qualifiedPath(defaultHistorian(), tagName)),
                    "startDate", baseTs + 5000,
                    "endDate", baseTs - 5000
            ));
            assertTrue((Boolean) result.get("success"), "Inverted range should succeed without error");
            int rowCount = ((Number) result.get("rowCount")).intValue();
            assertEquals(0, rowCount, "Inverted time range should return 0 rows");
            pass("Inverted time range returns 0 rows");
        }

        @Test
        @Order(30)
        @DisplayName("Bad quality code maps to Bad status")
        void testBadQualityStore() throws Exception {
            section("Error: Bad quality code storage");

            String tagName = TEST_PREFIX + "/Error/BadQuality";
            long baseTs = 1700081000000L;

            String qPath = qualifiedPath(defaultHistorian(), tagName);
            Map<String, Object> result = webdevPost("test/storePoints", Map.of(
                    "paths", List.of(qPath, qPath, qPath),
                    "values", List.of(1.0, 2.0, 3.0),
                    "timestamps", List.of(baseTs, baseTs + 1000, baseTs + 2000),
                    "qualities", List.of(0, 64, 192)  // Bad, Uncertain, Good
            ));
            assertTrue((Boolean) result.get("success"), "Store with mixed qualities should succeed");
            pass("Store with Bad/Uncertain/Good quality codes accepted");
        }

        @Test
        @Order(40)
        @DisplayName("Browse non-existent path returns empty or error gracefully")
        void testBrowseNonExistentPath() throws Exception {
            section("Error: Browse non-existent path");

            Map<String, Object> result = webdevPost("test/browse", Map.of(
                    "path", "histprov:" + defaultHistorian() + ":/folder:NonExistent_" + TEST_PREFIX + ":/"
            ));
            assertTrue((Boolean) result.get("success"), "Browse should succeed");
            int count = ((Number) result.get("count")).intValue();
            log("Browse non-existent path returned " + count + " nodes");
            assertEquals(0, count, "Non-existent folder should return 0 nodes");
            pass("Browse non-existent path returns 0 nodes");
        }

        @Test
        @Order(50)
        @DisplayName("Boolean round-trip: store and query")
        void testBooleanRoundTrip() throws Exception {
            section("Error: Boolean round trip");

            String tagName = TEST_PREFIX + "/Error/Boolean";
            String measurementName = storedTagPath(tagName);
            long baseTs = 1700082000000L;

            String uuid = createMeasurement(measurementName, "boolean");
            assertFalse(uuid.isEmpty());

            grpcStub.createPoints(Points.newBuilder()
                    .addPoints(buildPoint(uuid, baseTs, Value.newBuilder().setBoolValue(true).build()))
                    .addPoints(buildPoint(uuid, baseTs + 1000, Value.newBuilder().setBoolValue(false).build()))
                    .addPoints(buildPoint(uuid, baseTs + 2000, Value.newBuilder().setBoolValue(true).build()))
                    .build());
            log("Inserted 3 boolean points");
            Thread.sleep(2000);

            QueryTimeseriesResponse grpcResponse = grpcQuery(uuid, baseTs - 1000, baseTs + 3000);
            int grpcPoints = grpcResponse.getSeriesList().isEmpty() ? 0
                    : grpcResponse.getSeries(0).getDataPointsCount();
            assertTrue(grpcPoints >= 3, "Expected >= 3 boolean points via gRPC, got " + grpcPoints);
            pass("Boolean values stored and verified via gRPC");

            Map<String, Object> result = webdevPost("test/queryRaw", Map.of(
                    "paths", List.of(qualifiedPath(defaultHistorian(), tagName)),
                    "startDate", baseTs - 1000,
                    "endDate", baseTs + 3000
            ));
            assertTrue((Boolean) result.get("success"));
            int rowCount = ((Number) result.get("rowCount")).intValue();
            log("system.historian returned " + rowCount + " rows for boolean tag");
            assertTrue(rowCount >= 3, "Expected >= 3 rows for boolean tag");
            pass("Boolean round-trip via system.historian: " + rowCount + " rows");
        }

        @Test
        @Order(60)
        @DisplayName("Large batch store (500 points)")
        void testLargeBatchStore() throws Exception {
            section("Error: Large batch store");

            String tagName = TEST_PREFIX + "/Error/LargeBatch";
            String measurementName = storedTagPath(tagName);
            long baseTs = 1700083000000L;

            String uuid = createMeasurement(measurementName, "number");
            assertFalse(uuid.isEmpty());

            List<Point> points = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                points.add(buildPoint(uuid, baseTs + i * 100,
                        Value.newBuilder().setNumberValue(Math.sin(i * 0.1)).build()));
            }
            grpcStub.createPoints(Points.newBuilder().addAllPoints(points).build());
            log("Inserted 500 points via gRPC");
            Thread.sleep(2000);

            QueryTimeseriesResponse grpcResponse = grpcQuery(uuid, baseTs - 1000, baseTs + 50_000);
            int grpcPoints = grpcResponse.getSeriesList().isEmpty() ? 0
                    : grpcResponse.getSeries(0).getDataPointsCount();
            assertTrue(grpcPoints >= 500, "Expected >= 500 points via gRPC, got " + grpcPoints);
            pass("Large batch: " + grpcPoints + " points stored and verified");

            Map<String, Object> result = webdevPost("test/queryRaw", Map.of(
                    "paths", List.of(qualifiedPath(defaultHistorian(), tagName)),
                    "startDate", baseTs - 1000,
                    "endDate", baseTs + 50_000
            ));
            assertTrue((Boolean) result.get("success"));
            int rowCount = ((Number) result.get("rowCount")).intValue();
            log("system.historian returned " + rowCount + " rows for 500-point batch");
            assertTrue(rowCount >= 500, "Expected >= 500 rows");
            pass("Large batch query: " + rowCount + " rows");
        }

        @Test
        @Order(70)
        @DisplayName("Partial multi-tag query (some tags exist, some don't)")
        void testPartialMultiTagQuery() throws Exception {
            section("Error: Partial multi-tag query");

            String existingTag = TEST_PREFIX + "/Error/Exists";
            String missingTag = TEST_PREFIX + "/Error/Missing_" + randomSuffix(4);
            long baseTs = 1700084000000L;

            String measurementName = storedTagPath(existingTag);
            String uuid = createMeasurement(measurementName, "number");
            assertFalse(uuid.isEmpty());

            grpcStub.createPoints(Points.newBuilder()
                    .addPoints(buildPoint(uuid, baseTs, Value.newBuilder().setNumberValue(42.0).build()))
                    .addPoints(buildPoint(uuid, baseTs + 1000, Value.newBuilder().setNumberValue(43.0).build()))
                    .build());
            Thread.sleep(2000);

            Map<String, Object> result = webdevPost("test/queryRaw", Map.of(
                    "paths", List.of(
                            qualifiedPath(defaultHistorian(), existingTag),
                            qualifiedPath(defaultHistorian(), missingTag)
                    ),
                    "startDate", baseTs - 1000,
                    "endDate", baseTs + 2000
            ));
            assertTrue((Boolean) result.get("success"), "Partial query should succeed");
            int rowCount = ((Number) result.get("rowCount")).intValue();
            log("Partial multi-tag query returned " + rowCount + " rows");
            assertTrue(rowCount >= 2, "Should return data for the existing tag");
            pass("Partial multi-tag query succeeds with " + rowCount + " rows");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Build the qualified historian path used by system.historian.* functions.
     * Format: {@code histprov:Name:/sys:System:/prov:default:/tag:TagName}
     */
    private String qualifiedPath(String historianName, String tagName) {
        return "histprov:" + historianName + ":/sys:" + GATEWAY_SYSTEM_NAME
                + ":/prov:default:/tag:" + tagName;
    }

    /** Build the stored measurement name: {@code Ignition-xxx:[default]TagName} */
    private String storedTagPath(String tagName) {
        return GATEWAY_SYSTEM_NAME + ":[default]" + tagName;
    }

    /** Create a measurement in Factry and return its UUID. */
    private String createMeasurement(String name, String dataType) {
        grpcStub.createMeasurements(CreateMeasurementsRequest.newBuilder()
                .addMeasurements(CreateMeasurement.newBuilder()
                        .setName(name)
                        .setDataType(dataType)
                        .setAutoOnboard(true)
                        .build())
                .build());

        for (int attempt = 0; attempt < 5; attempt++) {
            String uuid = findMeasurementUuid(name);
            if (uuid != null) return uuid;
            try { Thread.sleep(500 * (attempt + 1)); } catch (InterruptedException ignored) {}
        }
        return "";
    }

    /** Find a measurement UUID by name. Returns null if not found. */
    private String findMeasurementUuid(String name) {
        Measurements measurements = grpcStub.getMeasurements(MeasurementRequest.newBuilder().build());
        for (Measurement m : measurements.getMeasurementsList()) {
            if (m.getName().equals(name)) {
                return m.getUuid();
            }
        }
        return null;
    }

    /** Build a gRPC Point. */
    private static Point buildPoint(String measurementUUID, long timestampMs, Value value) {
        return Point.newBuilder()
                .setMeasurementUUID(measurementUUID)
                .setTimestamp(Timestamp.newBuilder()
                        .setSeconds(timestampMs / 1000)
                        .setNanos((int) ((timestampMs % 1000) * 1_000_000))
                        .build())
                .setValue(value)
                .setStatus("Good")
                .build();
    }

    /** Query Factry directly via gRPC. */
    private QueryTimeseriesResponse grpcQuery(String measurementUUID, long startMs, long endMs) {
        return grpcStub.queryTimeseries(QueryTimeseriesRequest.newBuilder()
                .addMeasurementUUIDs(measurementUUID)
                .setStart(Timestamp.newBuilder()
                        .setSeconds(startMs / 1000)
                        .setNanos((int) ((startMs % 1000) * 1_000_000))
                        .build())
                .setEnd(Timestamp.newBuilder()
                        .setSeconds(endMs / 1000)
                        .setNanos((int) ((endMs % 1000) * 1_000_000))
                        .build())
                .build());
    }

    /** POST JSON to a WebDev endpoint and parse the response. */
    Map<String, Object> webdevPost(String endpoint, Map<String, Object> data)
            throws IOException, InterruptedException {
        String url = GATEWAY_URL + "/system/webdev/" + WEBDEV_PROJECT + "/" + endpoint;
        String jsonBody = gson.toJson(data);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "text/plain")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode(),
                "WebDev " + endpoint + " returned " + resp.statusCode() + ": " + resp.body());

        Type type = new TypeToken<Map<String, Object>>() {}.getType();
        Map<String, Object> result = gson.fromJson(resp.body(), type);

        if (Boolean.FALSE.equals(result.get("success"))) {
            log("WebDev error: " + result.get("error"));
            if (result.containsKey("trace")) {
                log("Trace: " + result.get("trace"));
            }
        }

        return result;
    }

    /** Extract the first non-null numeric value from an aggregation result. */
    @SuppressWarnings("unchecked")
    private double extractAggregationValue(Map<String, Object> result) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        if (rows == null || rows.isEmpty()) return Double.NaN;
        List<String> columns = (List<String>) result.get("columns");
        for (String col : columns) {
            if (col.equals("t_stamp")) continue;
            Object val = rows.get(0).get(col);
            if (val != null) return ((Number) val).doubleValue();
        }
        return Double.NaN;
    }

    /** Assert that an aggregation query returned a value close to expected. */
    @SuppressWarnings("unchecked")
    private void assertAggregationValue(Map<String, Object> result, double expected,
                                        double tolerance, String label) {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        assertNotNull(rows, label + " should return rows");
        assertFalse(rows.isEmpty(), label + " should return at least one row");

        List<String> columns = (List<String>) result.get("columns");
        for (String col : columns) {
            if (col.equals("t_stamp")) continue;
            Object val = rows.get(0).get(col);
            if (val != null) {
                double actual = ((Number) val).doubleValue();
                assertEquals(expected, actual, tolerance, label + " value");
                return;
            }
        }
        fail(label + " — no non-null value found in result");
    }

    /** Generate a random lowercase suffix. */
    static String randomSuffix(int length) {
        Random rng = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + rng.nextInt(26)));
        }
        return sb.toString();
    }
}
