package com.steamunblock.dns;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.steamunblock.util.Log;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SteamDomains {

    private final Map<String, List<String>> domainMap;
    private final List<String> patterns;

    private SteamDomains(Map<String, List<String>> domainMap, List<String> patterns) {
        this.domainMap = new ConcurrentHashMap<>(domainMap);
        this.patterns = List.copyOf(patterns);
    }

    public static SteamDomains loadDefault() {
        try (var is = SteamDomains.class.getResourceAsStream("/domains.json");
             Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            return parse(JsonParser.parseReader(reader).getAsJsonObject());
        } catch (Exception e) {
            Log.warn("Could not load default domains, using empty map: " + e.getMessage());
            return new SteamDomains(new HashMap<>(), List.of("steam", "valve"));
        }
    }

    public static SteamDomains loadFrom(Path file) throws IOException {
        String content = Files.readString(file, StandardCharsets.UTF_8);
        return parse(JsonParser.parseString(content).getAsJsonObject());
    }

    private static SteamDomains parse(JsonObject json) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        List<String> patterns = new ArrayList<>();

        if (json.has("patterns")) {
            for (JsonElement e : json.getAsJsonArray("patterns")) {
                patterns.add(e.getAsString());
            }
        }

        if (json.has("domains")) {
            JsonObject domains = json.getAsJsonObject("domains");
            for (var entry : domains.entrySet()) {
                List<String> ips = new ArrayList<>();
                for (JsonElement ip : entry.getValue().getAsJsonArray()) {
                    ips.add(ip.getAsString());
                }
                map.put(entry.getKey().toLowerCase(), ips);
            }
        }

        return new SteamDomains(map, patterns);
    }

    public boolean isSteamDomain(String hostname) {
        String lower = hostname.toLowerCase();
        if (domainMap.containsKey(lower)) return true;
        return patterns.stream().anyMatch(lower::contains);
    }

    public String getIp(String hostname) {
        List<String> ips = domainMap.get(hostname.toLowerCase());
        return (ips != null && !ips.isEmpty()) ? ips.getFirst() : null;
    }

    public void cacheResolution(String hostname, List<String> ips) {
        domainMap.put(hostname.toLowerCase(), ips);
        Log.debug(true, "Cached: " + hostname + " -> " + String.join(", ", ips));
    }

    public Map<String, List<String>> getAllEntries() {
        return Collections.unmodifiableMap(domainMap);
    }
}
