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
import java.util.*;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the Factry Historian Ignition module.
 * <p>
 * Requires running infrastructure:
 * <ul>
 *   <li>Ignition gateway with the Factry Historian module + WebDev module</li>
 *   <li>Factry Historian (gRPC)</li>
 *   <li>WebDev endpoints test/store and test/query in the configured project</li>
 * </ul>
 * <p>
 * Run via: {@code ./gradlew integrationTest}
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class FactryIntegrationTest {

    // -- Configuration (from system properties, set by Gradle) ----------------

    private static final String GATEWAY_URL = System.getProperty("gateway.url", "http://localhost:8089");
    private static final String WEBDEV_PROJECT = System.getProperty("webdev.project", "TestFactry");
    private static final String HISTORIAN_NAME = System.getProperty("historian.name", "Factry Historian 0.8");
    private static final String GRPC_HOST = System.getProperty("grpc.host", "localhost");
    private static final int GRPC_PORT = Integer.parseInt(System.getProperty("grpc.port", "8001"));
    private static final String COLLECTOR_UUID = System.getProperty("collector.uuid", "");
    private static final String COLLECTOR_TOKEN = System.getProperty("collector.token", "");
    private static final String GATEWAY_SYSTEM_NAME = System.getProperty("gateway.system.name", "Ignition-296a8ca4b6cd");

    /** Wait time for the module's batch flush (default 5s interval + margin). */
    private static final int BATCH_FLUSH_WAIT_MS = 8_000;

    /** Unique prefix per test run to avoid measurement cache collisions. */
    private static final String TEST_PREFIX = "IT" + randomSuffix(6);

    // -- Shared resources -----------------------------------------------------

    private ManagedChannel grpcChannel;
    private HistorianGrpc.HistorianBlockingStub grpcStub;
    private HttpClient httpClient;
    private final Gson gson = new Gson();

    // -- Setup / Teardown -----------------------------------------------------

    @BeforeAll
    void setup() {
        System.out.println("=== Factry Historian Integration Tests ===");
        System.out.println("  Test prefix:   " + TEST_PREFIX);
        System.out.println("  Gateway:       " + GATEWAY_URL);
        System.out.println("  Factry gRPC:   " + GRPC_HOST + ":" + GRPC_PORT);
        System.out.println("  Historian:     " + HISTORIAN_NAME);
        System.out.println("  System name:   " + GATEWAY_SYSTEM_NAME);

        // HTTP client for WebDev calls
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        // gRPC channel for direct Factry access
        grpcChannel = NettyChannelBuilder.forAddress(GRPC_HOST, GRPC_PORT)
                .usePlaintext()
                .build();

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

        // Verify connectivity
        assertDoesNotThrow(() -> checkGateway(), "Ignition gateway unreachable at " + GATEWAY_URL);
        assertDoesNotThrow(() -> checkFactryGrpc(), "Factry gRPC unreachable at " + GRPC_HOST + ":" + GRPC_PORT);
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

    // -- Connectivity checks --------------------------------------------------

    private void checkGateway() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(GATEWAY_URL + "/StatusPing"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, resp.statusCode(), "Gateway StatusPing failed");
        System.out.println("  Gateway:     OK");
    }

    private void checkFactryGrpc() {
        grpcStub.withDeadlineAfter(5, TimeUnit.SECONDS)
                .getMeasurements(MeasurementRequest.newBuilder().build());
        System.out.println("  Factry gRPC: OK");
    }

    // -- Test: Raw Query ------------------------------------------------------

    @Test
    @Order(10)
    @DisplayName("Insert data via gRPC, query via Ignition WebDev")
    void testRawQuery() throws Exception {
        String tagName = TEST_PREFIX + "/RawQuery";
        String measurementName = storedTagPath(tagName);

        // Create measurement directly in Factry
        String uuid = createMeasurement(measurementName, "number");
        assertNotNull(uuid, "Measurement UUID should not be null");
        assertFalse(uuid.isEmpty(), "Measurement UUID should not be empty");

        // Insert 5 data points directly via gRPC
        long baseTs = 1700000000000L;
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            points.add(buildPoint(uuid, baseTs + i * 1000, Value.newBuilder().setNumberValue(10.0 + i).build()));
        }
        grpcStub.createPoints(Points.newBuilder().addAllPoints(points).build());

        // Small delay for Factry to index
        Thread.sleep(2000);

        // Query via Ignition WebDev
        Map<String, Object> queryResult = gatewayQuery(
                List.of(histPath(tagName)),
                baseTs - 1000,
                baseTs + 5000,
                null, null
        );

        assertTrue((Boolean) queryResult.get("success"), "Query should succeed");
        int rowCount = ((Number) queryResult.get("rowCount")).intValue();
        assertTrue(rowCount >= 5, "Expected >= 5 rows, got " + rowCount);

        // Verify values
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) queryResult.get("rows");
        List<String> columns = findValueColumns(queryResult);
        if (!columns.isEmpty()) {
            List<Double> values = new ArrayList<>();
            for (int i = 0; i < 5 && i < rows.size(); i++) {
                Object val = rows.get(i).get(columns.get(0));
                values.add(((Number) val).doubleValue());
            }
            assertEquals(List.of(10.0, 11.0, 12.0, 13.0, 14.0), values, "Values should match inserted data");
        }
    }

    // -- Test: Store via Ignition ---------------------------------------------

    @Test
    @Order(20)
    @DisplayName("Store data via Ignition, verify in Factry via gRPC")
    void testStoreViaIgnition() throws Exception {
        String tagName = TEST_PREFIX + "/StoreTest";
        String measurementName = storedTagPath(tagName);
        long baseTs = 1700010000000L;

        // Store via WebDev → module → Factry
        Map<String, Object> storeResult = gatewayStore(
                tagName,
                List.of(100.0, 200.0, 300.0),
                List.of(baseTs, baseTs + 1000, baseTs + 2000)
        );
        assertTrue((Boolean) storeResult.get("success"), "Store call should succeed");

        // Wait for batch flush
        System.out.println("  Waiting " + (BATCH_FLUSH_WAIT_MS / 1000) + "s for batch flush...");
        Thread.sleep(BATCH_FLUSH_WAIT_MS);

        // Find the measurement UUID in Factry
        String uuid = findMeasurementUuid(measurementName);
        assertNotNull(uuid, "Measurement '" + measurementName + "' should exist in Factry after store");

        // Query directly via gRPC to verify
        QueryTimeseriesResponse response = grpcQuery(uuid, baseTs - 1000, baseTs + 3000);
        assertFalse(response.getSeriesList().isEmpty(), "Should return at least one series");

        Series series = response.getSeries(0);
        assertTrue(series.getDataPointsCount() >= 3,
                "Expected >= 3 points, got " + series.getDataPointsCount());

        List<Double> values = new ArrayList<>();
        for (int i = 0; i < 3 && i < series.getDataPointsCount(); i++) {
            values.add(series.getDataPoints(i).getValue().getNumberValue());
        }
        assertEquals(List.of(100.0, 200.0, 300.0), values, "Values should match what was stored");
    }

    // -- Test: Aggregation ----------------------------------------------------

    @Test
    @Order(30)
    @DisplayName("Aggregation queries (Average, Min, Max)")
    void testAggregation() throws Exception {
        String tagName = TEST_PREFIX + "/Aggregation";
        String measurementName = storedTagPath(tagName);

        String uuid = createMeasurement(measurementName, "number");
        assertFalse(uuid.isEmpty(), "Measurement UUID should not be empty");

        // Insert 100 points: values 0..99 over 100 seconds
        long baseTs = 1700020000000L;
        List<Point> points = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            points.add(buildPoint(uuid, baseTs + i * 1000,
                    Value.newBuilder().setNumberValue(i).build()));
        }
        grpcStub.createPoints(Points.newBuilder().addAllPoints(points).build());
        Thread.sleep(2000);

        String queryPath = histPath(tagName);

        // Average: mean of 0..99 = 49.5
        Map<String, Object> avgResult = gatewayQuery(
                List.of(queryPath), baseTs - 1000, baseTs + 100_000, "Average", 1);
        assertAggregationClose(avgResult, 49.5, 0.1, "Average");

        // Minimum: should be 0.0
        Map<String, Object> minResult = gatewayQuery(
                List.of(queryPath), baseTs - 1000, baseTs + 100_000, "Minimum", 1);
        assertAggregationClose(minResult, 0.0, 0.5, "Minimum");

        // Maximum: should be 99.0
        Map<String, Object> maxResult = gatewayQuery(
                List.of(queryPath), baseTs - 1000, baseTs + 100_000, "Maximum", 1);
        assertAggregationClose(maxResult, 99.0, 0.5, "Maximum");
    }

    // -- Test: Multi-tag query ------------------------------------------------

    @Test
    @Order(40)
    @DisplayName("Query multiple tags simultaneously")
    void testMultiTagQuery() throws Exception {
        long baseTs = 1700030000000L;
        List<String> tagNames = List.of(
                TEST_PREFIX + "/Multi0",
                TEST_PREFIX + "/Multi1",
                TEST_PREFIX + "/Multi2"
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
        }
        Thread.sleep(2000);

        List<String> paths = tagNames.stream().map(this::histPath).toList();
        Map<String, Object> result = gatewayQuery(paths, baseTs - 1000, baseTs + 5000, null, null);

        assertTrue((Boolean) result.get("success"));
        @SuppressWarnings("unchecked")
        List<String> columns = (List<String>) result.get("columns");
        // Expect t_stamp + 3 value columns
        assertTrue(columns.size() >= 4,
                "Expected >= 4 columns (t_stamp + 3 tags), got " + columns);
        assertTrue(((Number) result.get("rowCount")).intValue() >= 5,
                "Expected >= 5 rows");
    }

    // -- Test: Empty query ----------------------------------------------------

    @Test
    @Order(50)
    @DisplayName("Query a time range with no data returns empty result")
    void testEmptyQuery() throws Exception {
        String tagName = TEST_PREFIX + "/Empty";
        String measurementName = storedTagPath(tagName);

        createMeasurement(measurementName, "number");

        Map<String, Object> result = gatewayQuery(
                List.of(histPath(tagName)),
                1600000000000L, 1600000010000L,
                null, null
        );
        assertTrue((Boolean) result.get("success"));
        assertEquals(0, ((Number) result.get("rowCount")).intValue(), "Should return 0 rows for empty range");
    }

    // -- Test: String values --------------------------------------------------

    @Test
    @Order(60)
    @DisplayName("Store and query string-type tag data")
    void testStringValues() throws Exception {
        String tagName = TEST_PREFIX + "/StringTest";
        String measurementName = storedTagPath(tagName);

        String uuid = createMeasurement(measurementName, "string");
        assertFalse(uuid.isEmpty());

        long baseTs = 1700040000000L;
        List<Point> points = List.of(
                buildPoint(uuid, baseTs, Value.newBuilder().setStringValue("hello").build()),
                buildPoint(uuid, baseTs + 1000, Value.newBuilder().setStringValue("world").build()),
                buildPoint(uuid, baseTs + 2000, Value.newBuilder().setStringValue("test").build())
        );
        grpcStub.createPoints(Points.newBuilder().addAllPoints(points).build());
        Thread.sleep(2000);

        Map<String, Object> result = gatewayQuery(
                List.of(histPath(tagName)),
                baseTs - 1000, baseTs + 3000,
                null, null
        );
        assertTrue((Boolean) result.get("success"));
        assertTrue(((Number) result.get("rowCount")).intValue() >= 3,
                "Expected >= 3 rows for string query");
    }

    // -- Test: Round trip (store + query via Ignition) -------------------------

    @Test
    @Order(70)
    @DisplayName("Full round trip: store via Ignition, query via Ignition")
    void testRoundTrip() throws Exception {
        String tagName = TEST_PREFIX + "/RoundTrip";
        long baseTs = 1700050000000L;

        // Store via Ignition
        Map<String, Object> storeResult = gatewayStore(
                tagName,
                List.of(42.0, 43.0, 44.0, 45.0, 46.0),
                List.of(baseTs, baseTs + 1000, baseTs + 2000, baseTs + 3000, baseTs + 4000)
        );
        assertTrue((Boolean) storeResult.get("success"));

        System.out.println("  Waiting " + (BATCH_FLUSH_WAIT_MS / 1000) + "s for batch flush...");
        Thread.sleep(BATCH_FLUSH_WAIT_MS);

        // Query via Ignition
        Map<String, Object> queryResult = gatewayQuery(
                List.of(histPath(tagName)),
                baseTs - 1000, baseTs + 5000,
                null, null
        );
        assertTrue((Boolean) queryResult.get("success"));
        assertTrue(((Number) queryResult.get("rowCount")).intValue() >= 5,
                "Expected >= 5 rows");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) queryResult.get("rows");
        List<String> valueCols = findValueColumns(queryResult);
        if (!valueCols.isEmpty()) {
            List<Double> values = new ArrayList<>();
            for (int i = 0; i < 5 && i < rows.size(); i++) {
                Object val = rows.get(i).get(valueCols.get(0));
                values.add(((Number) val).doubleValue());
            }
            assertEquals(List.of(42.0, 43.0, 44.0, 45.0, 46.0), values,
                    "Round-trip values should match");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /** Build the stored measurement name: {@code Ignition-xxx:[default]TagName} */
    private String storedTagPath(String tagName) {
        return GATEWAY_SYSTEM_NAME + ":[default]" + tagName;
    }

    /** Build the historian query path: {@code histprov:Name:/tag:Ignition-xxx/default/TagName} */
    private String histPath(String tagName) {
        return "histprov:" + HISTORIAN_NAME + ":/tag:" + GATEWAY_SYSTEM_NAME + "/default/" + tagName;
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

        // Fetch UUID (measurement may take a moment to be visible)
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

    /** Build a gRPC Point with the given timestamp and value. */
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

    // -- WebDev HTTP helpers --------------------------------------------------

    /** POST to the WebDev store endpoint. */
    private Map<String, Object> gatewayStore(String tagName, List<Double> values, List<Long> timestamps)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("provider", HISTORIAN_NAME);
        body.put("tagProvider", "default");
        body.put("paths", List.of(tagName));
        body.put("values", List.of(values));
        body.put("timestamps", timestamps);
        body.put("qualities", timestamps.stream().map(t -> 192).toList());
        return webdevPost("test/store", body);
    }

    /** POST to the WebDev query endpoint. */
    private Map<String, Object> gatewayQuery(List<String> paths, long startMs, long endMs,
                                              String aggregationMode, Integer returnSize)
            throws IOException, InterruptedException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("paths", paths);
        body.put("startDate", startMs);
        body.put("endDate", endMs);
        if (aggregationMode != null) body.put("aggregationMode", aggregationMode);
        if (returnSize != null) body.put("returnSize", returnSize);
        return webdevPost("test/query", body);
    }

    /** POST JSON to a WebDev endpoint and parse the response. */
    private Map<String, Object> webdevPost(String endpoint, Map<String, Object> data)
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
        return gson.fromJson(resp.body(), type);
    }

    // -- Assertion helpers ----------------------------------------------------

    /** Extract value column names (everything except t_stamp). */
    @SuppressWarnings("unchecked")
    private static List<String> findValueColumns(Map<String, Object> queryResult) {
        List<String> columns = (List<String>) queryResult.get("columns");
        if (columns == null) return List.of();
        return columns.stream().filter(c -> !c.equals("t_stamp")).toList();
    }

    /** Assert that an aggregation query returned a single value close to expected. */
    @SuppressWarnings("unchecked")
    private void assertAggregationClose(Map<String, Object> result, double expected,
                                        double tolerance, String label) {
        assertTrue((Boolean) result.get("success"), label + " query should succeed");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) result.get("rows");
        assertNotNull(rows, label + " should return rows");
        assertFalse(rows.isEmpty(), label + " should return at least one row");

        List<String> valueCols = findValueColumns(result);
        assertFalse(valueCols.isEmpty(), label + " should have value columns");

        Object val = rows.get(0).get(valueCols.get(0));
        assertNotNull(val, label + " value should not be null");
        double actual = ((Number) val).doubleValue();
        assertEquals(expected, actual, Math.abs(expected * tolerance) + tolerance,
                label + " value");
    }

    /** Generate a random lowercase suffix. */
    private static String randomSuffix(int length) {
        Random rng = new Random();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) ('a' + rng.nextInt(26)));
        }
        return sb.toString();
    }
}
