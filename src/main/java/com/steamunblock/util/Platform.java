package com.steamunblock.util;

import java.io.IOException;
import java.nio.file.Path;

public final class Platform {

    public enum OS { WINDOWS, LINUX, MACOS }

    private Platform() {}

    public static OS detect() {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) return OS.WINDOWS;
        if (os.contains("mac") || os.contains("darwin")) return OS.MACOS;
        return OS.LINUX;
    }

    public static Path getHostsFilePath() {
        return switch (detect()) {
            case WINDOWS -> Path.of(System.getenv("SystemRoot"), "System32", "drivers", "etc", "hosts");
            case LINUX, MACOS -> Path.of("/etc/hosts");
        };
    }

    public static void flushDnsCache() {
        try {
            ProcessBuilder pb = switch (detect()) {
                case WINDOWS -> new ProcessBuilder("ipconfig", "/flushdns");
                case MACOS -> new ProcessBuilder("dscacheutil", "-flushcache");
                case LINUX -> new ProcessBuilder("systemd-resolve", "--flush-caches");
            };
            pb.redirectErrorStream(true);
            Process p = pb.start();
            p.waitFor();
        } catch (IOException | InterruptedException e) {
            Log.debug(true, "DNS flush failed: " + e.getMessage());
        }
    }
}
