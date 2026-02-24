package io.factry.historian.gateway;

import io.factry.historian.proto.HistorianCollectorGrpc;
import io.factry.historian.proto.StoreRequest;
import io.factry.historian.proto.StoreResponse;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;

/**
 * gRPC client for sending tag data to the Factry Historian proxy.
 */
public class FactryGrpcClient {
    private static final Logger logger = LoggerFactory.getLogger(FactryGrpcClient.class);

    private final ManagedChannel channel;
    private final HistorianCollectorGrpc.HistorianCollectorBlockingStub blockingStub;

    public FactryGrpcClient(String host, int port) {
        this(ManagedChannelBuilder.forAddress(host, port).usePlaintext().build());
    }

    FactryGrpcClient(ManagedChannel channel) {
        this.channel = channel;
        this.blockingStub = HistorianCollectorGrpc.newBlockingStub(channel);
        logger.info("gRPC client created, target={}",  channel.authority());
    }

    /**
     * Send a StoreRequest to the proxy.
     *
     * @return the StoreResponse from the proxy
     * @throws StatusRuntimeException if the RPC fails
     */
    public StoreResponse store(StoreRequest request) {
        logger.debug("Sending StoreRequest with {} samples", request.getSamplesCount());
        StoreResponse response = blockingStub.store(request);
        logger.debug("StoreResponse: success={}, message={}, count={}",
                response.getSuccess(), response.getMessage(), response.getCount());
        return response;
    }

    /**
     * Gracefully shut down the gRPC channel.
     */
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

    /**
     * Check if the channel is terminated.
     */
    public boolean isShutdown() {
        return channel.isShutdown();
    }
}
