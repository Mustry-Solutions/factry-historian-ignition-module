package io.factry.historian.gateway;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.inductiveautomation.ignition.common.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;

/**
 * Parses the payload of a Factry Historian JWT collector token without
 * verifying the signature. Used to extract connection defaults
 * (collector UUID, host, gRPC port) so the user only needs to paste the token.
 */
public final class JwtTokenParser {
    private static final Logger logger = LoggerFactory.getLogger(JwtTokenParser.class);

    private JwtTokenParser() {
    }

    /**
     * Decode the JWT payload section (second dot-separated segment).
     *
     * @return parsed JSON object, or empty if the token is malformed
     */
    public static Optional<JsonObject> parsePayload(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                logger.debug("Token does not look like a JWT (no dot separators)");
                return Optional.empty();
            }
            byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
            String json = new String(decoded, StandardCharsets.UTF_8);
            return Optional.of(JsonParser.parseString(json).getAsJsonObject());
        } catch (Exception e) {
            logger.debug("Failed to parse JWT payload: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /** Extract the collector UUID from the {@code uuid} claim. */
    public static Optional<String> getCollectorUUID(JsonObject payload) {
        return getStringField(payload, "uuid");
    }

    /** Extract the collector name from the {@code name} claim. */
    public static Optional<String> getCollectorName(JsonObject payload) {
        return getStringField(payload, "name");
    }

    /** Extract the host from the {@code aud} (audience) claim, which is a URL like {@code http://historian}. */
    public static Optional<String> getHost(JsonObject payload) {
        return getStringField(payload, "aud").map(aud -> {
            try {
                return URI.create(aud).getHost();
            } catch (Exception e) {
                // aud might be a plain hostname
                return aud;
            }
        });
    }

    /** Extract the gRPC port from the {@code grpc-port} claim. */
    public static Optional<Integer> getGrpcPort(JsonObject payload) {
        return getStringField(payload, "grpc-port").map(port -> {
            try {
                return Integer.parseInt(port);
            } catch (NumberFormatException e) {
                return null;
            }
        });
    }

    private static Optional<String> getStringField(JsonObject obj, String field) {
        if (obj != null && obj.has(field) && !obj.get(field).isJsonNull()) {
            String value = obj.get(field).getAsString();
            return value.isBlank() ? Optional.empty() : Optional.of(value);
        }
        return Optional.empty();
    }
}
