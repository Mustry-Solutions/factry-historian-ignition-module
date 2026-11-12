package io.factry.historian.gateway;

import com.codahale.metrics.MetricRegistry;
import com.inductiveautomation.historian.common.model.data.AtomicPoint;
import com.inductiveautomation.historian.common.model.data.DataPointFactory;
import com.inductiveautomation.historian.common.model.data.StorageResult;
import com.inductiveautomation.ignition.common.QualifiedPath;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import io.factry.historian.proto.CreatePointsReply;
import io.factry.historian.proto.GetMeasurementsByFilterRequest;
import io.factry.historian.proto.HistorianGrpc;
import io.factry.historian.proto.Measurement;
import io.factry.historian.proto.Measurements;
import io.factry.historian.proto.Points;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * On a slow link a full store and forward batch takes longer to send than a small one. The
 * write deadline must grow with the batch, or the same batch times out on every retry and the
 * backlog never shrinks. Limits come from the test copy of factry-historian.properties.
 */
class SlowLinkTest {

    /** Time the fake Factry needs per point: 10,000 points take 3 s, three times the base deadline. */
    private static final long NANOS_PER_POINT = 300_000;

    private Server server;
    private FactryGrpcClient client;
    private final AtomicInteger storedPoints = new AtomicInteger();

    private class SlowFactry extends HistorianGrpc.HistorianImplBase {
        @Override
        public void getMeasurementsByFilter(GetMeasurementsByFilterRequest request,
                                            StreamObserver<Measurements> observer) {
            observer.onNext(Measurements.newBuilder()
                    .addMeasurements(Measurement.newBuilder()
                            .setUuid("uuid-" + request.getKeyword())
                            .setName(request.getKeyword())
                            .setStatus("Active"))
                    .setTotal(1)
                    .build());
            observer.onCompleted();
        }

        @Override
        public void createPoints(Points request, StreamObserver<CreatePointsReply> observer) {
            try {
                TimeUnit.NANOSECONDS.sleep(NANOS_PER_POINT * request.getPointsCount());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            storedPoints.addAndGet(request.getPointsCount());
            observer.onNext(CreatePointsReply.getDefaultInstance());
            observer.onCompleted();
        }
    }

    @BeforeEach
    void startServer() throws Exception {
        server = NettyServerBuilder.forPort(0).addService(new SlowFactry()).build().start();
        client = new FactryGrpcClient("localhost", server.getPort(), "collector-uuid", "token",
                false, false, null);
    }

    @AfterEach
    void stopServer() throws Exception {
        server.shutdownNow().awaitTermination(5, TimeUnit.SECONDS);
        client.shutdown();
    }

    private static FactryStorageEngine engine(FactryGrpcClient client) {
        // The engine only asks the context for a metric registry; fail loudly on anything else.
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

    private static List<AtomicPoint<?>> batch(int size) {
        QualifiedPath path = QualifiedPath.newBuilder().setProvider("default").setTag("Pump1/Flow").build();
        Instant start = Instant.now().minusSeconds(size);
        List<AtomicPoint<?>> points = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            points.add(DataPointFactory.createAtomicPoint((double) i, QualityCode.Good, start.plusSeconds(i), path));
        }
        return points;
    }

    @Test
    void largeBatchOnSlowLinkIsStored() throws Exception {
        StorageResult<AtomicPoint<?>> result = engine(client)
                .storeAtomic(batch(10_000)).toCompletableFuture().get(60, TimeUnit.SECONDS);

        assertTrue(result.error().isEmpty(), "batch failed: " + result.error());
        assertEquals(10_000, storedPoints.get());
    }
}
