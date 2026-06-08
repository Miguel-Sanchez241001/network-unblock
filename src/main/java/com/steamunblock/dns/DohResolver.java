package com.steamunblock.dns;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steamunblock.util.Log;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class DohResolver {

    private final HttpClient httpClient;
    private final String dohBaseUrl;

    public DohResolver(String dohBaseUrl) {
        this.dohBaseUrl = dohBaseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    public String resolve(String hostname) {
        List<String> ips = resolveAll(hostname);
        return ips.isEmpty() ? null : ips.getFirst();
    }

    public List<String> resolveAll(String hostname) {
        try {
            String url = dohBaseUrl + "?name="
                    + URLEncoder.encode(hostname, StandardCharsets.UTF_8) + "&type=A";

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(
                    request, HttpResponse.BodyHandlers.ofString());

            JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
            if (json.has("Answer")) {
                JsonArray answers = json.getAsJsonArray("Answer");
                List<String> ips = new ArrayList<>();
                for (JsonElement elem : answers) {
                    JsonObject answer = elem.getAsJsonObject();
                    if (answer.get("type").getAsInt() == 1) {
                        ips.add(answer.get("data").getAsString());
                    }
                }
                return ips;
            }
        } catch (Exception e) {
            Log.debug(true, "DoH resolve failed for " + hostname + ": " + e.getMessage());
        }
        return Collections.emptyList();
    }
}
