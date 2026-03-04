package io.factry.historian.gateway;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.inductiveautomation.ignition.common.util.LoggerEx;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Scanner;

/**
 * HTTP client for communicating with the Factry Historian proxy REST API.
 *
 * This class handles all HTTP communication with the proxy, including:
 * - POST to /collector for storing tag data
 * - POST to /provider for querying historical data
 */
public class FactryHttpClient {

    private static final LoggerEx logger = LoggerEx.newBuilder().build(FactryHttpClient.class);
    private final FactryHistorianSettings settings;
    private final Gson gson;

    public FactryHttpClient(FactryHistorianSettings settings) {
        this.settings = settings;
        this.gson = new GsonBuilder().create();
    }

    /**
     * Send samples to the collector endpoint.
     *
     * @param samples List of tag samples to store
     * @return CollectorResponse containing success status and message
     * @throws IOException if HTTP communication fails
     */
    public CollectorResponse sendToCollector(List<TagSample> samples) throws IOException {
        String url = "http://" + settings.getGrpcHost() + ":8111/collector";

        CollectorRequest request = new CollectorRequest();
        request.samples = samples;

        String requestJson = gson.toJson(request);

        if (settings.isDebugLogging()) {
            logger.debug("POST " + url);
            logger.debug("Request: " + requestJson);
        }

        String responseJson = doPost(url, requestJson);

        if (settings.isDebugLogging()) {
            logger.debug("Response: " + responseJson);
        }

        return gson.fromJson(responseJson, CollectorResponse.class);
    }

    /**
     * Query historical data from the provider endpoint.
     *
     * @param tagPaths List of tag paths to query
     * @param startTime Start timestamp in milliseconds
     * @param endTime End timestamp in milliseconds
     * @param maxPoints Maximum number of points to return per tag
     * @return ProviderResponse containing historical data
     * @throws IOException if HTTP communication fails
     */
    public ProviderResponse queryFromProvider(List<String> tagPaths, long startTime, long endTime, int maxPoints) throws IOException {
        String url = "http://" + settings.getGrpcHost() + ":8111/provider";

        ProviderRequest request = new ProviderRequest();
        request.tagPaths = tagPaths;
        request.startTime = startTime;
        request.endTime = endTime;
        request.maxPoints = maxPoints;

        String requestJson = gson.toJson(request);

        if (settings.isDebugLogging()) {
            logger.debug("POST " + url);
            logger.debug("Request: " + requestJson);
        }

        String responseJson = doPost(url, requestJson);

        if (settings.isDebugLogging()) {
            logger.debug("Response: " + responseJson);
        }

        return gson.fromJson(responseJson, ProviderResponse.class);
    }

    /**
     * Perform HTTP POST request.
     *
     * @param urlString URL to POST to
     * @param jsonBody JSON request body
     * @return Response body as String
     * @throws IOException if HTTP communication fails
     */
    private String doPost(String urlString, String jsonBody) throws IOException {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        try {
            // Configure request
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setDoOutput(true);

            // Write request body
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Read response
            int responseCode = conn.getResponseCode();

            if (responseCode >= 200 && responseCode < 300) {
                // Success response
                try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8.name())) {
                    return scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
                }
            } else {
                // Error response
                String errorBody = "";
                try (Scanner scanner = new Scanner(conn.getErrorStream(), StandardCharsets.UTF_8.name())) {
                    errorBody = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
                }
                throw new IOException("HTTP " + responseCode + ": " + errorBody);
            }

        } finally {
            conn.disconnect();
        }
    }

    // Request/Response DTOs

    public static class TagSample {
        public String tagPath;
        public long timestamp;
        public Object value;
        public int quality;
    }

    public static class CollectorRequest {
        public List<TagSample> samples;
    }

    public static class CollectorResponse {
        public boolean success;
        public String message;
        public int count;
    }

    public static class ProviderRequest {
        public List<String> tagPaths;
        public long startTime;
        public long endTime;
        public int maxPoints;
    }

    public static class ProviderResponse {
        public boolean success;
        public String message;
        public Map<String, List<TagSample>> data;
    }
}
