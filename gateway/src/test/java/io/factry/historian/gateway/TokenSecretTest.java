package io.factry.historian.gateway;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.gateway.config.ValidationErrors;
import com.inductiveautomation.ignition.gateway.secrets.SecretConfig;
import io.factry.historian.gateway.IgnitionHistoryQuery.Dataset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The collector token is a secret (#14): Embedded (encrypted with the gateway's key) or Referenced
 * (read from a secret provider), never stored or shown in clear. Profiles saved before keep their
 * plain {@code token} working until the next save, which removes it.
 */
class TokenSecretTest {

    private static final long START = 1_700_000_000_000L;

    private final FakeSecrets secrets = new FakeSecrets();
    private FakeFactryServer factry;
    private String token;
    private FactryHistoryProvider provider;

    @BeforeEach
    void setUp() throws Exception {
        factry = new FakeFactryServer(TestTls.LOOPBACK);
        String uuid = factry.addMeasurement("default/a", "number");
        factry.addPoint(uuid, START, 1.0);
        token = jwt(factry.port(), "sig-one");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (provider != null) {
            provider.onShutdown();
        }
        factry.close();
    }

    @Test
    void embeddedTokenConnects() throws Exception {
        provider = start(saved("collectorToken", FakeSecrets.embedded(token).toJsonTree()));

        assertReads();
        assertOnlySent(token);
    }

    @Test
    void referencedTokenConnects() throws Exception {
        provider = start(saved("collectorToken", secrets.referenced("vault", "factry", token + "\n").toJsonTree()));

        assertReads();
        assertOnlySent(token);
    }

    @Test
    void plainTokenOfAnOlderProfileStillConnectsWithOneWarning() throws Exception {
        try (LogCapture log = new LogCapture(FactryHistoryProvider.class.getName(), Level.INFO)) {
            provider = start(saved("token", new com.inductiveautomation.ignition.common.gson.JsonPrimitive(token)));

            List<ILoggingEvent> warnings = log.events().stream().filter(e -> e.getLevel() == Level.WARN).toList();
            assertEquals(1, warnings.size(), log.text());
            assertTrue(warnings.get(0).getFormattedMessage().contains("plain text"));
            assertFalse(log.text().contains("sig-one"), "the log must not contain the token");
        }
        assertReads();
        assertOnlySent(token);
    }

    @Test
    void savingWithTheSecretRemovesThePlainCopy() throws Exception {
        // What the form sends for an older profile once the token is entered as a secret: the old
        // key may come along.
        JsonObject posted = new JsonObject();
        posted.addProperty("token", token);
        posted.add("collectorToken", FakeSecrets.embedded(token).toJsonTree());

        FactryHistorianExtensionPoint ep = new FactryHistorianExtensionPoint();
        JsonElement stored = ep.encode(ep.decode(posted));

        assertFalse(stored.getAsJsonObject().has("token"), "the plain token must not be stored again");
        assertEquals("Embedded", stored.getAsJsonObject().getAsJsonObject("collectorToken").get("type").getAsString());
        assertFalse(stored.toString().contains("sig-one"));
    }

    @Test
    void tokenRotatedInTheSecretProviderIsPickedUpWhenFactryRefusesTheOldOne() throws Exception {
        provider = start(saved("collectorToken", secrets.referenced("vault", "factry", token).toJsonTree()));
        String rotated = jwt(factry.port(), "sig-two");

        // Factry revokes the old token; the operator puts the new one in the provider.
        factry.acceptedToken = rotated;
        secrets.providers.get("vault").put("factry", rotated);
        provider.checkConnection();

        factry.authorizations.clear();
        assertReads();
        assertOnlySent(rotated);
    }

    @Test
    void saveRefusesAMissingToken() throws Exception {
        assertEquals(List.of("collectorToken"), fieldsInError(new JsonObject()));
    }

    @Test
    void saveRefusesAnEmbeddedValueThatIsNotACollectorToken() throws Exception {
        JsonObject settings = new JsonObject();
        settings.add("collectorToken", FakeSecrets.embedded("not-a-token secretValue").toJsonTree());

        Optional<ValidationErrors> errors = validate(settings);

        assertEquals(List.of("collectorToken"), fieldsInError(settings));
        assertFalse(errors.orElseThrow().toString().contains("secretValue"), "the message must not show the token");
    }

    @Test
    void saveAcceptsAReferencedTokenWithoutCallingTheProvider() throws Exception {
        JsonObject settings = new JsonObject();
        settings.add("collectorToken", SecretConfig.referenced("nowhere", "factry").toJsonTree());

        assertTrue(validate(settings).isEmpty());
    }

    private Optional<ValidationErrors> validate(JsonObject settings) throws Exception {
        FactryHistorianExtensionPoint ep = new FactryHistorianExtensionPoint();
        ep.setGatewayContext(secrets.gatewayContext());
        return ep.getSettingsValidator().orElseThrow().validate(ep.decode(settings));
    }

    private List<String> fieldsInError(JsonObject settings) throws Exception {
        return validate(settings).orElseThrow().fieldMessages().stream()
                .map(ValidationErrors.FieldValidationErrors::fieldName).toList();
    }

    private FactryHistoryProvider start(FactryHistorianSettings settings) {
        return new FactryHistoryProvider(secrets.gatewayContext(), "Factry", settings);
    }

    private void assertReads() throws Exception {
        Dataset ds = IgnitionHistoryQuery.wide(provider.getQueryEngine().orElseThrow(), START, START + 1_000, -1, null,
                "default/a");
        assertNull(ds.error);
        assertEquals(1, ds.rows.size());
    }

    private void assertOnlySent(String expected) {
        assertFalse(factry.authorizations.isEmpty());
        // Compare without printing: a failure message must not carry the token.
        for (String sent : factry.authorizations) {
            assertTrue(sent.equals("Bearer " + expected), "the authorization header is not the expected token");
        }
    }

    /** Settings as Ignition decodes them from the saved profile. */
    private static FactryHistorianSettings saved(String key, JsonElement tokenValue) throws Exception {
        JsonObject config = new JsonObject();
        config.add(key, tokenValue);
        config.addProperty("delimiter", "/");
        config.addProperty("customCaCert", TestTls.caPem());
        return new FactryHistorianExtensionPoint().decode(config);
    }

    /** A collector token for the fake Factry; the signature is not checked. */
    private static String jwt(int port, String signature) {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        String payload = enc.encodeToString(("{\"uuid\":\"collector\",\"name\":\"Ignition\","
                + "\"aud\":\"http://127.0.0.1\",\"grpc-port\":\"" + port + "\"}").getBytes(StandardCharsets.UTF_8));
        return header + "." + payload + "." + signature;
    }
}
