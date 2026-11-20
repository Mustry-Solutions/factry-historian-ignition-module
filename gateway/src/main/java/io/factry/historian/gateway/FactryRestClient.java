package io.factry.historian.gateway;

import com.inductiveautomation.ignition.common.gson.JsonElement;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedTrustManager;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * The one thing the module asks of Factry's REST API: the collector's default time-series
 * database, which Factry's gRPC {@code Collector} does not carry. Factry v8.2.3 answers
 * {@code GET /api/collectors/<uuid>} to the collector token itself, with the organisation from
 * the token in {@code x-organization-uuid}. The REST port is the token's {@code rest-port} claim,
 * on the gRPC host. HTTPS when the profile uses TLS, with the same certificate checks; never
 * plain HTTP then, so the token is not sent in the clear.
 */
final class FactryRestClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private FactryRestClient() {
    }

    /** The default database UUID, or null when the token does not say where REST is or Factry does not say. */
    static String defaultDatabaseUUID(String host, String token, boolean useTls, boolean skipTlsVerification,
                                      String customCaCert) throws Exception {
        JsonObject claims = JwtTokenParser.parsePayload(token).orElse(null);
        Integer port = claims != null ? JwtTokenParser.getRestPort(claims).orElse(null) : null;
        String organization = claims != null ? JwtTokenParser.getOrganizationUUID(claims).orElse(null) : null;
        String collector = claims != null ? JwtTokenParser.getCollectorUUID(claims).orElse(null) : null;
        if (port == null || organization == null || collector == null) {
            return null;
        }
        String uriHost = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        URI uri = URI.create((useTls ? "https" : "http") + "://" + uriHost + ":" + port + "/api/collectors/" + collector);

        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(TIMEOUT);
        if (useTls) {
            builder.sslContext(sslContext(skipTlsVerification, customCaCert));
        }
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(TIMEOUT)
                .header("Authorization", "Bearer " + token)
                .header("x-organization-uuid", organization)
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> response = builder.build().send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " from " + uri.getPath());
        }
        JsonElement database = JsonParser.parseString(response.body()).getAsJsonObject().get("DefaultDatabaseUUID");
        return database == null || database.isJsonNull() || database.getAsString().isBlank()
                ? null : database.getAsString();
    }

    private static SSLContext sslContext(boolean skipTlsVerification, String customCaCert) throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        if (skipTlsVerification) {
            context.init(null, new TrustManager[] {new TrustEverything()}, null);
        } else {
            // The same trust as the gRPC channel: system CAs, the bundled Factry CA, a custom CA.
            try (InputStream bundled = FactryRestClient.class.getClassLoader()
                    .getResourceAsStream("factry-historian-ca.crt")) {
                context.init(null, FactryGrpcClient.buildCombinedTrustManagerFactory(bundled, customCaCert)
                        .getTrustManagers(), null);
            }
        }
        return context;
    }

    /** As the gRPC channel's skip-verification mode: no certificate or host name check. */
    private static final class TrustEverything extends X509ExtendedTrustManager {
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) { }
        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) { }
        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) { }
        @Override public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }
}
