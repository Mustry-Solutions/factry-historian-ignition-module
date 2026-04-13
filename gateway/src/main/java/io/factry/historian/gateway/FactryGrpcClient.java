package io.factry.historian.gateway;

import io.factry.historian.proto.Assets;
import io.factry.historian.proto.CreateMeasurementsReply;
import io.factry.historian.proto.CreateMeasurementsRequest;
import io.factry.historian.proto.CreatePointsReply;
import io.factry.historian.proto.GetAssetsRequest;
import io.factry.historian.proto.HistorianGrpc;
import io.factry.historian.proto.MeasurementRequest;
import io.factry.historian.proto.Measurements;
import io.factry.historian.proto.Points;
import io.factry.historian.proto.QueryTimeseriesRequest;
import io.factry.historian.proto.QueryTimeseriesResponse;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.grpc.stub.MetadataUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class FactryGrpcClient {
    private static final Logger logger = LoggerFactory.getLogger(FactryGrpcClient.class);
    private static final Metadata.Key<String> COLLECTOR_UUID_KEY =
            Metadata.Key.of("collectoruuid", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> AUTHORIZATION_KEY =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER);

    private static final long WRITE_DEADLINE_SECONDS = ModuleProperties.getWriteDeadlineSeconds();
    private static final String CA_CERT_RESOURCE = "factry-historian-ca.crt";

    /**
     * Guards channel/stub access during reconfiguration.
     * gRPC calls take the read lock (concurrent), reconfigure takes the write lock (exclusive).
     */
    private final ReadWriteLock channelLock = new ReentrantReadWriteLock();

    private volatile ManagedChannel channel;
    private volatile HistorianGrpc.HistorianBlockingStub blockingStub;
    private volatile boolean connected = true;

    public FactryGrpcClient(String host, int port, String collectorUUID, String token,
                            boolean useTls, boolean skipTlsVerification) {
        buildChannel(host, port, collectorUUID, token, useTls, skipTlsVerification);
        logger.info("gRPC client created ({}), target={}:{}, collectorUUID={}",
                tlsLabel(useTls, skipTlsVerification), host, port, collectorUUID);
    }

    public CreatePointsReply createPoints(Points points) {
        logger.debug("Sending CreatePoints with {} points", points.getPointsCount());
        channelLock.readLock().lock();
        try {
            CreatePointsReply reply = blockingStub
                    .withDeadlineAfter(WRITE_DEADLINE_SECONDS, TimeUnit.SECONDS)
                    .createPoints(points);
            connected = true;
            return reply;
        } catch (StatusRuntimeException e) {
            Status.Code code = e.getStatus().getCode();
            if (code == Status.Code.UNAVAILABLE || code == Status.Code.DEADLINE_EXCEEDED) {
                connected = false;
            }
            throw e;
        } finally {
            channelLock.readLock().unlock();
        }
    }

    public Measurements getMeasurements(MeasurementRequest request) {
        logger.debug("Sending GetMeasurements");
        channelLock.readLock().lock();
        try {
            return blockingStub.getMeasurements(request);
        } finally {
            channelLock.readLock().unlock();
        }
    }

    public QueryTimeseriesResponse queryTimeseries(QueryTimeseriesRequest request) {
        logger.debug("Sending QueryTimeseries for {} measurements", request.getMeasurementUUIDsCount());
        channelLock.readLock().lock();
        try {
            return blockingStub.queryTimeseries(request);
        } finally {
            channelLock.readLock().unlock();
        }
    }

    public Assets getAssets() {
        logger.debug("Sending GetAssets");
        channelLock.readLock().lock();
        try {
            return blockingStub.getAssets(GetAssetsRequest.newBuilder().build());
        } finally {
            channelLock.readLock().unlock();
        }
    }

    public CreateMeasurementsReply createMeasurements(CreateMeasurementsRequest request) {
        logger.debug("Sending CreateMeasurements with {} measurements", request.getMeasurementsCount());
        channelLock.readLock().lock();
        try {
            return blockingStub.createMeasurements(request);
        } finally {
            channelLock.readLock().unlock();
        }
    }

    /**
     * Reconfigure the client with new connection settings. Shuts down the old
     * gRPC channel and creates a new one.
     */
    public void reconfigure(String host, int port, String collectorUUID, String token,
                            boolean useTls, boolean skipTlsVerification) {
        logger.info("Reconfiguring gRPC client: {}:{}", host, port);
        channelLock.writeLock().lock();
        try {
            shutdownChannel();
            buildChannel(host, port, collectorUUID, token, useTls, skipTlsVerification);
            logger.info("gRPC client reconfigured ({}), target={}:{}, collectorUUID={}",
                    tlsLabel(useTls, skipTlsVerification), host, port, collectorUUID);
        } finally {
            channelLock.writeLock().unlock();
        }
    }

    private void buildChannel(String host, int port, String collectorUUID, String token,
                              boolean useTls, boolean skipTlsVerification) {
        if (useTls) {
            try {
                var sslBuilder = GrpcSslContexts.forClient();
                if (skipTlsVerification) {
                    sslBuilder.trustManager(InsecureTrustManagerFactory.INSTANCE);
                } else {
                    InputStream caCert = getClass().getClassLoader().getResourceAsStream(CA_CERT_RESOURCE);
                    if (caCert != null) {
                        sslBuilder.trustManager(caCert);
                        logger.info("Using bundled Factry CA certificate for TLS verification");
                    } else {
                        logger.warn("Bundled CA certificate '{}' not found, falling back to system trust store",
                                CA_CERT_RESOURCE);
                    }
                }
                this.channel = NettyChannelBuilder.forAddress(host, port)
                        .sslContext(sslBuilder.build())
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
    }

    private static String tlsLabel(boolean useTls, boolean skipTlsVerification) {
        if (!useTls) return "plaintext";
        return skipTlsVerification ? "TLS (insecure)" : "TLS";
    }

    public void shutdown() {
        channelLock.writeLock().lock();
        try {
            shutdownChannel();
        } finally {
            channelLock.writeLock().unlock();
        }
    }

    /** Shuts down the channel. Caller must hold the write lock. */
    private void shutdownChannel() {
        logger.info("Shutting down gRPC channel");
        if (channel == null) {
            return;
        }
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

    public boolean isConnected() {
        return connected;
    }

    public void markConnected() {
        connected = true;
    }

    /**
     * Test connectivity by making a lightweight gRPC call.
     *
     * @return true if the server responds
     */
    public boolean testConnection() {
        channelLock.readLock().lock();
        try {
            blockingStub.withDeadlineAfter(5, TimeUnit.SECONDS)
                    .getMeasurements(MeasurementRequest.newBuilder().build());
            connected = true;
            return true;
        } catch (Exception e) {
            connected = false;
            logger.debug("Connection test failed: {}", e.getMessage());
            return false;
        } finally {
            channelLock.readLock().unlock();
        }
    }
}
