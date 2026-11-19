package io.factry.historian.gateway;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import io.factry.historian.gateway.IgnitionHistoryQuery.Dataset;
import io.grpc.Status;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Factry's gRPC port always uses TLS (#55). A new profile connects with TLS without any TLS field;
 * when the token says Factry serves its built-in certificate, that certificate is checked against
 * Factry's CA as historian.factry.local, whatever host the gateway connects to; and a failed check
 * says which setting to change.
 */
class TlsConnectionTest {

    private static final long START = 1_700_000_000_000L;
    private static final String LOOPBACK = InetAddress.getLoopbackAddress().getHostAddress();

    private final List<AutoCloseable> open = new ArrayList<>();

    @AfterEach
    void tearDown() throws Exception {
        for (AutoCloseable c : open) {
            c.close();
        }
    }

    @Test
    void newProfileWithOnlyATokenConnectsWithTls() throws Exception {
        FakeFactryServer factry = server(TestTls.LOOPBACK);
        String uuid = factry.addMeasurement("default/a", "number");
        factry.addPoint(uuid, START, 1.0);
        JsonObject config = new JsonObject();
        config.add("collectorToken", FakeSecrets.embedded(jwt(factry.port(), false)).toJsonTree());
        config.addProperty("customCaCert", TestTls.caPem());

        FactryHistoryProvider provider = new FactryHistoryProvider(new FakeSecrets().gatewayContext(), "Factry",
                new FactryHistorianExtensionPoint().decode(config));
        open.add(provider::onShutdown);

        Dataset ds = IgnitionHistoryQuery.wide(provider.getQueryEngine().orElseThrow(), START, START + 1_000, -1, null,
                "default/a");
        assertNull(ds.error);
        assertEquals(1, ds.rows.size());
    }

    @Test
    void factrysBuiltInCertificateIsCheckedAsHistorianFactryLocal() throws Exception {
        FakeFactryServer factry = server(TestTls.FACTRY_BUILT_IN);

        Status check = client(factry, true, null).checkConnection();

        assertTrue(check.isOk(), check.toString());
    }

    @Test
    void withoutTheClaimTheCertificateMustNameTheHost() throws Exception {
        FakeFactryServer factry = server(TestTls.FACTRY_BUILT_IN);

        Status check = client(factry, false, TestTls.caPem()).checkConnection();

        assertEquals(Status.Code.UNAVAILABLE, check.getCode());
        assertTrue(FactryGrpcClient.tlsHint(check).contains("does not name the host"), FactryGrpcClient.tlsHint(check));
    }

    @Test
    void certificateFromAnUnknownCaSaysToPasteTheCa() throws Exception {
        FakeFactryServer factry = server(TestTls.LOOPBACK);

        Status check = client(factry, false, null).checkConnection();

        assertEquals(Status.Code.UNAVAILABLE, check.getCode());
        assertTrue(WriteHealth.checkFailure(check).details(check).contains("Custom CA Certificate"),
                WriteHealth.checkFailure(check).details(check));
    }

    /** Factry behind a proxy with a certificate of its own: the token still says built-in. */
    @Test
    void customCaWinsOverTheClaim() throws Exception {
        FakeFactryServer factry = server(TestTls.LOOPBACK);

        Status check = client(factry, true, TestTls.caPem()).checkConnection();

        assertTrue(check.isOk(), check.toString());
    }

    @Test
    void serverWithoutTlsSaysToCheckHostAndPort() throws Exception {
        FakeFactryServer plain = server(null);

        Status check = client(plain, false, TestTls.caPem()).checkConnection();

        assertEquals(Status.Code.UNAVAILABLE, check.getCode());
        assertTrue(FactryGrpcClient.tlsHint(check).contains("does not answer with TLS"), FactryGrpcClient.tlsHint(check));
    }

    private FakeFactryServer server(String certificate) throws Exception {
        FakeFactryServer server = new FakeFactryServer(certificate);
        open.add(server);
        return server;
    }

    /** A client to 127.0.0.1 with the test CA standing in for Factry's. */
    private FactryGrpcClient client(FakeFactryServer server, boolean factryCertificate, String customCa) {
        FactryGrpcClient client = new FactryGrpcClient(LOOPBACK, server.port(), "collector", "token", true, false,
                customCa, factryCertificate, TestTls.ca());
        open.add(0, client::shutdown);
        return client;
    }

    private static String jwt(int port, boolean factryCertificate) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"uuid\":\"collector\",\"name\":\"Ignition\","
                + "\"aud\":\"http://127.0.0.1\",\"grpc-port\":\"" + port + "\","
                + "\"use-self-signed-certificate\":" + factryCertificate + "}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + ".sig";
    }
}
