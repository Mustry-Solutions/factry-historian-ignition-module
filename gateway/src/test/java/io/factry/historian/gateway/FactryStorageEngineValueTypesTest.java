package io.factry.historian.gateway;

import com.codahale.metrics.MetricRegistry;
import com.google.protobuf.Value;
import com.inductiveautomation.historian.common.model.data.AtomicPoint;
import com.inductiveautomation.historian.common.model.data.DataPointFactory;
import com.inductiveautomation.historian.common.model.data.MetadataPoint;
import com.inductiveautomation.historian.common.model.data.StorageResult;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.config.BasicPropertySet;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.common.tags.config.properties.WellKnownTagProps;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import io.factry.historian.proto.CreateMeasurement;
import io.factry.historian.proto.CreateMeasurementsReply;
import io.factry.historian.proto.CreateMeasurementsRequest;
import io.factry.historian.proto.CreatePointsReply;
import io.factry.historian.proto.GetMeasurementsByFilterRequest;
import io.factry.historian.proto.HistorianGrpc;
import io.factry.historian.proto.Measurement;
import io.factry.historian.proto.Measurements;
import io.factry.historian.proto.Point;
import io.factry.historian.proto.Points;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every value type Ignition historizes either reaches Factry in a form that reads back
 * faithfully, or is reported as not stored. Driven through the real engine against an in-test
 * gRPC server that behaves like Factry for lookups, creation and writes.
 */
class FactryStorageEngineValueTypesTest {

    private Server server;
    private FactryGrpcClient client;
    private FactryStorageEngine engine;

    /** Measurement name → data type it was created with. */
    private final Map<String, String> created = new ConcurrentHashMap<>();
    /** Measurement name → settings it was created with. */
    private final Map<String, com.google.protobuf.Struct> createdSettings = new ConcurrentHashMap<>();
    private final List<Point> written = new CopyOnWriteArrayList<>();

    private class FakeFactry extends HistorianGrpc.HistorianImplBase {
        @Override
        public void getMeasurementsByFilter(GetMeasurementsByFilterRequest request,
                                            StreamObserver<Measurements> observer) {
            Measurements.Builder reply = Measurements.newBuilder();
            String type = created.get(request.getKeyword());
            if (type != null) {
                reply.addMeasurements(Measurement.newBuilder()
                        .setUuid("uuid-" + request.getKeyword())
                        .setName(request.getKeyword())
                        .setDatatype(type)
                        .setStatus("Active"));
            }
            observer.onNext(reply.setTotal(reply.getMeasurementsCount()).build());
            observer.onCompleted();
        }

        @Override
        public void createMeasurements(CreateMeasurementsRequest request,
                                       StreamObserver<CreateMeasurementsReply> observer) {
            for (CreateMeasurement m : request.getMeasurementsList()) {
                created.put(m.getName(), m.getDataType());
                createdSettings.put(m.getName(), m.getSettings());
            }
            observer.onNext(CreateMeasurementsReply.getDefaultInstance());
            observer.onCompleted();
        }

        @Override
        public void createPoints(Points request, StreamObserver<CreatePointsReply> observer) {
            written.addAll(request.getPointsList());
            observer.onNext(CreatePointsReply.getDefaultInstance());
            observer.onCompleted();
        }
    }

    @BeforeEach
    void start() throws Exception {
        server = NettyServerBuilder.forPort(0).addService(new FakeFactry()).build().start();
        client = new FactryGrpcClient("localhost", server.getPort(), "collector-uuid", "token",
                false, false, null);
        engine = engine(client);
    }

    @AfterEach
    void stop() throws Exception {
        client.shutdown();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private static FactryStorageEngine engine(FactryGrpcClient client) {
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
        return new FactryStorageEngine(context, "factry", new FactryHistorianSettings(), client,
                new MeasurementCache(), new HistorianMetrics());
    }

    private static QualifiedPath path(String tag) {
        return QualifiedPath.newBuilder().setProvider("default").setTag(tag).build();
    }

    private static AtomicPoint<?> point(String tag, Object value, long epochMillis) {
        return DataPointFactory.createAtomicPoint(value, QualityCode.Good, Instant.ofEpochMilli(epochMillis),
                path(tag));
    }

    private StorageResult<AtomicPoint<?>> store(AtomicPoint<?>... points) throws Exception {
        return engine.storeAtomic(List.of(points)).toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private Point writtenFor(String measurementName) {
        return written.stream()
                .filter(p -> p.getMeasurementUUID().equals("uuid-" + measurementName))
                .findFirst()
                .orElseThrow(() -> new AssertionError("nothing written for " + measurementName));
    }

    /** Runs the action and returns what the engine logged to stderr (slf4j-simple) meanwhile. */
    private static String logOf(ThrowingRunnable action) throws Exception {
        PrintStream original = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured, true));
        try {
            action.run();
        } finally {
            System.setErr(original);
        }
        return captured.toString();
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static int count(String haystack, String needle) {
        Matcher m = Pattern.compile(Pattern.quote(needle)).matcher(haystack);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /** Every value class DataPointFactory accepts; AtomicPoint is sealed, so nothing else can arrive. */
    static Stream<Object> everyAtomicValueType() {
        return Stream.of(true, (byte) 1, (short) 2, 3, 4L, 5.5f, 6.5, "text", new Date(7_000L));
    }

    @ParameterizedTest
    @MethodSource("everyAtomicValueType")
    void everyValueTypeIsWrittenBeforeItIsReportedAsStored(Object value) throws Exception {
        String tag = "Types/" + value.getClass().getSimpleName();
        AtomicPoint<?> point = point(tag, value, 1_000L);

        StorageResult<AtomicPoint<?>> result = store(point);

        assertEquals(List.of(point), result.successfulData());
        assertTrue(writtenFor("default/" + tag).hasValue(), "nothing was sent for " + value.getClass());
    }

    @Test
    void dateIsStoredAsEpochMillisInANumberMeasurement() throws Exception {
        long when = 1_794_493_871_153L;
        AtomicPoint<?> date = point("Line/LastBatchStart", new Date(when), 1_000L);

        StorageResult<AtomicPoint<?>> result = store(date);

        assertEquals("number", created.get("default/Line/LastBatchStart"));
        Point sent = writtenFor("default/Line/LastBatchStart");
        assertEquals(Value.KindCase.NUMBER_VALUE, sent.getValue().getKindCase());
        assertEquals((double) when, sent.getValue().getNumberValue());
        assertEquals("Good", sent.getStatus());
        assertEquals(List.of(date), result.successfulData());
    }

    @Test
    void dateTimeTagIsMarkedAsDateTimeOnItsMeasurement() throws Exception {
        // The read side only gets tag paths, so the measurement must say its numbers are dates.
        BasicPropertySet props = new BasicPropertySet();
        props.set(WellKnownTagProps.DataType, DataType.DateTime);
        engine.storeComplex(List.of(MetadataPoint.from(props, path("Line/Stamp"))))
                .toCompletableFuture().get(30, TimeUnit.SECONDS);
        // A Date stored without metadata (for example from a script) is marked too.
        store(point("Line/Stamp", new Date(7_000L), 1_000L), point("Script/Stamp", new Date(7_000L), 1_000L),
                point("Line/Speed", 1.5, 1_000L));

        for (String name : List.of("default/Line/Stamp", "default/Script/Stamp")) {
            assertEquals("DateTime", createdSettings.get(name).getFieldsMap()
                    .get(MeasurementCache.IGNITION_DATA_TYPE_SETTING).getStringValue(), name);
        }
        assertEquals(0, createdSettings.get("default/Line/Speed").getFieldsCount());
    }

    @Test
    void booleanTagGetsABooleanMeasurementWhenValuesArriveAsLong() throws Exception {
        // Store and Forward persists Boolean values as Int8, so they come back as Long 0/1. The tag's
        // data type arrives as metadata before the first value.
        BasicPropertySet props = new BasicPropertySet();
        props.set(WellKnownTagProps.DataType, DataType.Boolean);
        engine.storeComplex(List.of(MetadataPoint.from(props, path("Test/Flag"))))
                .toCompletableFuture().get(30, TimeUnit.SECONDS);

        StorageResult<AtomicPoint<?>> result = store(point("Test/Flag", 1L, 1_000L));

        assertEquals("boolean", created.get("default/Test/Flag"));
        assertTrue(result.failedData().isEmpty());
    }

    @Test
    void nanAndInfinityAreStoredAsBadPointsWithoutAValue() throws Exception {
        String log = logOf(() -> {
            store(point("Pump/Flow", Double.NaN, 1_000L), point("Pump/Speed", Float.POSITIVE_INFINITY, 1_000L));
            store(point("Pump/Flow", Double.NaN, 2_000L));
        });

        for (String name : List.of("default/Pump/Flow", "default/Pump/Speed")) {
            Point sent = writtenFor(name);
            assertEquals("Bad", sent.getStatus(), name);
            // Factry turns NaN into 0 and keeps an Uncertain status; no value at all keeps it honest.
            assertFalse(sent.hasValue(), name);
        }
        assertEquals(1, count(log, "Tag 'default/Pump/Flow' has value NaN"),
                "warn once per tag, not per point:\n" + log);
    }

    @Test
    void nanInsideAnArrayIsStoredAsABadPointWithoutAValue() throws Exception {
        AtomicPoint<?>[] withNan = {
                point("Line/Temps[0]", 1.0, 1_000L), point("Line/Temps[1]", Double.NaN, 1_000L)};
        // Array measurements are created on a background thread; Store and Forward retries meanwhile.
        long deadline = System.currentTimeMillis() + 15_000L;
        String log = "";
        while (written.isEmpty() && System.currentTimeMillis() < deadline) {
            log += logOf(() -> store(withNan));
            Thread.sleep(50);
        }
        log += logOf(() -> store(point("Line/Temps[1]", 2.0, 2_000L)));

        List<Point> rows = written.stream()
                .filter(p -> p.getMeasurementUUID().equals("uuid-default/Line/Temps"))
                .toList();
        Point nanRow = rows.stream().filter(p -> p.getTimestamp().getSeconds() == 1).findFirst().orElseThrow();
        assertEquals("Bad", nanRow.getStatus());
        // Factry stores an array with a NaN element as an empty array; send no value at all.
        assertFalse(nanRow.hasValue());
        Point nextRow = rows.stream().filter(p -> p.getTimestamp().getSeconds() == 2).findFirst().orElseThrow();
        assertEquals("Good", nextRow.getStatus());
        assertEquals(List.of(1.0, 2.0), nextRow.getValue().getListValue().getValuesList().stream()
                .map(Value::getNumberValue).toList());
        assertEquals(1, count(log, "Array tag 'default/Line/Temps' has element value NaN"),
                "warn once per tag, not per point:\n" + log);
    }

    @Test
    void longAbove2pow53IsSentAsTheNearestDoubleAndWarnedOncePerTag() throws Exception {
        long big = (1L << 53) + 1;
        String log = logOf(() -> {
            store(point("Counter/Total", big, 1_000L), point("Counter/Small", 42L, 1_000L));
            store(point("Counter/Total", big, 2_000L));
        });

        assertEquals((double) (1L << 53), writtenFor("default/Counter/Total").getValue().getNumberValue());
        assertEquals(1, count(log, "Tag 'default/Counter/Total' has value " + big),
                "warn once per tag, not per point:\n" + log);
        assertEquals(0, count(log, "default/Counter/Small"), log);
    }
}
