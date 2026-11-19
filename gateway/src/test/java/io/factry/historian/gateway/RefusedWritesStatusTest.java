package io.factry.historian.gateway;

import com.codahale.metrics.MetricRegistry;
import com.inductiveautomation.historian.common.model.data.AtomicPoint;
import com.inductiveautomation.historian.common.model.data.DataPointFactory;
import com.inductiveautomation.historian.common.model.data.StorageResult;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.model.ProfileStatus;
import io.factry.historian.proto.Collector;
import io.factry.historian.proto.CreatePointsReply;
import io.factry.historian.proto.GetMeasurementsByFilterRequest;
import io.factry.historian.proto.HistorianGrpc;
import io.factry.historian.proto.Measurement;
import io.factry.historian.proto.Measurements;
import io.factry.historian.proto.Points;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Factry can answer the connection check (GetCollector) while it refuses every write, for example
 * INTERNAL while its InfluxDB is down (#59). Store and forward keeps that data, but the profile
 * status must say that nothing gets stored. The status here is what the provider's getStatus()
 * returns once started: the same {@link WriteHealth}, fed by the real storage engine.
 */
class RefusedWritesStatusTest {

    private Server server;
    private FactryGrpcClient client;
    private volatile Status createPointsStatus = Status.OK;
    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final WriteHealth health = new WriteHealth(now::get);
    private FactryStorageEngine engine;

    private class Factry extends HistorianGrpc.HistorianImplBase {
        @Override
        public void getCollector(com.google.protobuf.Empty request, StreamObserver<Collector> observer) {
            observer.onNext(Collector.newBuilder().setUuid("collector-uuid").setStatus("Active").build());
            observer.onCompleted();
        }

        @Override
        public void getMeasurementsByFilter(GetMeasurementsByFilterRequest request,
                                            StreamObserver<Measurements> observer) {
            observer.onNext(Measurements.newBuilder()
                    .addMeasurements(Measurement.newBuilder().setUuid("uuid-" + request.getKeyword())
                            .setName(request.getKeyword()).setStatus("Active"))
                    .setTotal(1).build());
            observer.onCompleted();
        }

        @Override
        public void createPoints(Points request, StreamObserver<CreatePointsReply> observer) {
            if (createPointsStatus.isOk()) {
                observer.onNext(CreatePointsReply.getDefaultInstance());
                observer.onCompleted();
            } else {
                observer.onError(createPointsStatus.asRuntimeException());
            }
        }
    }

    @BeforeEach
    void start() throws Exception {
        server = NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                .addService(new Factry()).build().start();
        client = new FactryGrpcClient(InetAddress.getLoopbackAddress().getHostAddress(), server.getPort(),
                "collector-uuid", "token", false, false, null);
        MetricRegistry registry = new MetricRegistry();
        GatewayContext context = (GatewayContext) Proxy.newProxyInstance(
                GatewayContext.class.getClassLoader(), new Class<?>[]{GatewayContext.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getMetricRegistry")) {
                        return registry;
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
        engine = new FactryStorageEngine(context, "factry", new FactryHistorianSettings(), client,
                new MeasurementCache(), new HistorianMetrics(), health);
        // What the provider's scheduled connection check does
        health.connectionChecked(client.checkConnection());
    }

    @AfterEach
    void stop() throws Exception {
        client.shutdown();
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
    }

    private StorageResult<AtomicPoint<?>> flush() throws Exception {
        AtomicPoint<?> point = DataPointFactory.createAtomicPoint(42.0, QualityCode.Good, Instant.now(),
                QualifiedPath.newBuilder().setProvider("default").setTag("Pump1/Flow").build());
        return engine.storeAtomic(List.of(point)).toCompletableFuture().get(30, TimeUnit.SECONDS);
    }

    private void after(long seconds) {
        now.addAndGet(seconds * 1000);
    }

    @Test
    void everyWriteRefusedFor30SecondsTurnsTheProfileErroredUntilOneIsAccepted() throws Exception {
        createPointsStatus = Status.INTERNAL.withDescription("influxdb: connection refused");

        assertTrue(flush().error().isPresent(), "store and forward keeps the refused flush");
        assertEquals(ProfileStatus.State.Good, health.status().getState(), "one refusal is no alarm");
        after(29);
        flush();
        assertEquals(ProfileStatus.State.Good, health.status().getState());

        after(1);
        health.connectionChecked(client.checkConnection());
        ProfileStatus status = health.status();
        assertEquals(ProfileStatus.State.Errored, status.getState(), "the connection check passes, writes don't");
        assertEquals("Factry has refused every write for 30 s (last answer: INTERNAL: influxdb: connection"
                + " refused). Store and forward keeps the data and retries it.", status.getErrorDetails());

        createPointsStatus = Status.OK;
        assertEquals(StorageResult.Type.SUCCESS, flush().type());
        assertSame(ProfileStatus.RUNNING, health.status(), "back at the first accepted write");
    }

    @Test
    void anAcceptedWriteInBetweenStartsTheCountAgain() throws Exception {
        createPointsStatus = Status.INTERNAL.withDescription("refused");
        flush();
        after(20);
        createPointsStatus = Status.OK;
        flush();
        createPointsStatus = Status.INTERNAL.withDescription("refused");
        after(20);
        flush();

        after(20);
        assertEquals(ProfileStatus.State.Good, health.status().getState(), "refused for 20 s only");
        after(10);
        assertEquals(ProfileStatus.State.Errored, health.status().getState());
    }

    @Test
    void notReachingFactryIsLeftToTheConnectionCheck() throws Exception {
        createPointsStatus = Status.UNAVAILABLE.withDescription("io exception");
        flush();
        after(60);
        flush();

        assertEquals(ProfileStatus.State.Good, health.status().getState());
    }

    @Test
    void newSettingsJudgeWritesAfresh() throws Exception {
        createPointsStatus = Status.INTERNAL.withDescription("refused");
        flush();
        after(30);
        assertEquals(ProfileStatus.State.Errored, health.status().getState());

        health.clearWrites();

        assertEquals(ProfileStatus.State.Good, health.status().getState());
    }

    @Test
    void aFailedConnectionCheckIsErrored() {
        health.connectionChecked(Status.UNAVAILABLE.withDescription("io exception"));

        assertEquals(ProfileStatus.State.Errored, health.status().getState());
    }
}
