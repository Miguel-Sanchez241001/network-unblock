package com.steamunblock.hosts;

import com.steamunblock.util.Platform;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Cross-platform hosts file editor. Manages entries between marker comments
 * to allow clean addition and removal of Steam domain mappings.
 */
public class HostsFileEditor {

    private static final String MARKER_START = "# STEAM-UNBLOCK-START - Do not edit manually";
    private static final String MARKER_END = "# STEAM-UNBLOCK-END";

    private final Path hostsPath;

    public HostsFileEditor() {
        this.hostsPath = Platform.getHostsFilePath();
    }

    /**
     * Adds domain-to-IP entries to the hosts file, wrapped in markers.
     * Removes existing steam-unblock entries first to avoid duplicates.
     */
    public void addEntries(Map<String, String> domainToIp) throws IOException {
        List<String> lines = Files.readAllLines(hostsPath, StandardCharsets.UTF_8);

        // Remove existing managed entries
        lines = removeManagedBlock(lines);

        // Append new managed block
        lines.add("");
        lines.add(MARKER_START);
        for (var entry : domainToIp.entrySet()) {
            lines.add(entry.getValue() + "    " + entry.getKey());
        }
        lines.add(MARKER_END);

        String lineEnding = Platform.detect() == Platform.OS.WINDOWS ? "\r\n" : "\n";
        Files.writeString(hostsPath, String.join(lineEnding, lines) + lineEnding, StandardCharsets.UTF_8);
    }

    /**
     * Removes all steam-unblock managed entries from the hosts file.
     * Returns the number of entries removed.
     */
    public int removeEntries() throws IOException {
        List<String> lines = Files.readAllLines(hostsPath, StandardCharsets.UTF_8);
        int originalSize = lines.size();

        lines = removeManagedBlock(lines);

        // Remove trailing blank lines that were before our block
        while (!lines.isEmpty() && lines.getLast().isBlank()) {
            lines.removeLast();
        }

        String lineEnding = Platform.detect() == Platform.OS.WINDOWS ? "\r\n" : "\n";
        Files.writeString(hostsPath, String.join(lineEnding, lines) + lineEnding, StandardCharsets.UTF_8);

        return originalSize - lines.size();
    }

    /**
     * Returns the managed entries currently in the hosts file.
     */
    public List<String> showEntries() throws IOException {
        List<String> lines = Files.readAllLines(hostsPath, StandardCharsets.UTF_8);
        List<String> managed = new ArrayList<>();
        boolean inBlock = false;

        for (String line : lines) {
            if (line.trim().equals(MARKER_START)) {
                inBlock = true;
                continue;
            }
            if (line.trim().equals(MARKER_END)) {
                break;
            }
            if (inBlock && !line.isBlank()) {
                managed.add(line.trim());
            }
        }

        return managed;
    }

    private List<String> removeManagedBlock(List<String> lines) {
        List<String> result = new ArrayList<>();
        boolean inBlock = false;

        for (String line : lines) {
            if (line.trim().equals(MARKER_START)) {
                inBlock = true;
                continue;
            }
            if (line.trim().equals(MARKER_END)) {
                inBlock = false;
                continue;
            }
            if (!inBlock) {
                result.add(line);
            }
        }

        return result;
    }
}
