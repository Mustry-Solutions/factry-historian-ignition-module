package io.factry.historian.gateway;

import io.factry.historian.proto.CreateMeasurementsReply;
import io.factry.historian.proto.CreateMeasurementsRequest;
import io.factry.historian.proto.CreatePointsReply;
import io.factry.historian.proto.HistorianGrpc;
import io.factry.historian.proto.MeasurementRequest;
import io.factry.historian.proto.Measurements;
import io.factry.historian.proto.Points;
import io.factry.historian.proto.QueryPointsReply;
import io.factry.historian.proto.QueryRawPointsRequest;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.grpc.stub.MetadataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.util.concurrent.TimeUnit;

public class FactryGrpcClient {
    private static final Logger logger = LoggerFactory.getLogger(FactryGrpcClient.class);
    private static final Metadata.Key<String> COLLECTOR_UUID_KEY =
            Metadata.Key.of("collectoruuid", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private final ManagedChannel channel;
    private final HistorianGrpc.HistorianBlockingStub blockingStub;

    public FactryGrpcClient(String host, int port, String collectorUUID, String token, boolean useTls) {
        if (useTls) {
            try {
                SslContext sslContext = GrpcSslContexts.forClient()
                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                        .build();

                this.channel = NettyChannelBuilder.forAddress(host, port)
                        .sslContext(sslContext)
                        .build();
            } catch (SSLException e) {
                throw new RuntimeException("Failed to create TLS-enabled gRPC channel", e);
            }
        } else {
            this.channel = NettyChannelBuilder.forAddress(host, port)
                    .usePlaintext()
                    .build();
        }

        Metadata headers = new Metadata();
        headers.put(COLLECTOR_UUID_KEY, collectorUUID);
        headers.put(AUTHORIZATION_KEY, "Bearer " + token);

        this.blockingStub = HistorianGrpc.newBlockingStub(channel)
                .withInterceptors(MetadataUtils.newAttachHeadersInterceptor(headers));

        logger.info("gRPC client created ({}), target={}:{}, collectorUUID={}",
                useTls ? "TLS" : "plaintext", host, port, collectorUUID);
    }

    public CreatePointsReply createPoints(Points points) {
        logger.debug("Sending CreatePoints with {} points", points.getPointsCount());
        return blockingStub.createPoints(points);
    }

    public Measurements getMeasurements(MeasurementRequest request) {
        logger.debug("Sending GetMeasurements");
        return blockingStub.getMeasurements(request);
    }

    public QueryPointsReply queryRawPoints(QueryRawPointsRequest request) {
        logger.debug("Sending QueryRawPoints for {} measurements", request.getMeasurementUUIDsCount());
        return blockingStub.queryRawPoints(request);
    }

    public CreateMeasurementsReply createMeasurements(CreateMeasurementsRequest request) {
        logger.debug("Sending CreateMeasurements with {} measurements", request.getMeasurementsCount());
        return blockingStub.createMeasurements(request);
    }

    public void shutdown() {
        logger.info("Shutting down gRPC channel");
        try {
            channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            logger.warn("gRPC channel shutdown interrupted, forcing shutdown");
            channel.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public boolean isShutdown() {
        return channel.isShutdown();
    }
}
