package io.factry.historian.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The collector's default time-series database, which tells the module its own Site. Factry's gRPC
 * API does not give it; its REST API does, at {@code GET /api/collectors/<uuid>}, to the collector
 * token itself (checked live on 8.2.3), on the port of the token's {@code rest-port} claim.
 */
class DefaultDatabaseTest {

    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{\"Name\":\"ignition-site-a\",\"DefaultDatabaseUUID\":\"db-a\"}";

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                    + " auth=" + exchange.getRequestHeaders().getFirst("Authorization")
                    + " org=" + exchange.getRequestHeaders().getFirst("x-organization-uuid"));
            byte[] answer = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String token() {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"uuid\":\"collector-a\",\"organization-uuid\":\"org-1\","
                + "\"grpc-port\":\"1\",\"rest-port\":\"" + server.getAddress().getPort() + "\"}")
                .getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }

    private String ask() {
        FactryGrpcClient client = new FactryGrpcClient("127.0.0.1", 1, "collector-a", token(), false, false, null);
        try {
            return client.getDefaultDatabaseUUID();
        } finally {
            client.shutdown();
        }
    }

    @Test
    void askedOfFactrysRestApiWithTheCollectorToken() {
        assertEquals("db-a", ask());
        assertEquals(List.of("GET /api/collectors/collector-a auth=Bearer " + token() + " org=org-1"), requests);
    }

    @Test
    void unknownWhenFactryRefusesOrDoesNotSay() {
        status = 403;
        assertNull(ask());
        status = 200;
        body = "{\"Name\":\"ignition-site-a\",\"DefaultDatabaseUUID\":null}";
        assertNull(ask());
    }
}
