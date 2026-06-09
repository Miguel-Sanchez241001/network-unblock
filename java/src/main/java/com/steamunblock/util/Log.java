package com.steamunblock.util;

public final class Log {

    private Log() {}

    private static final String CYAN    = "\u001b[36m";
    private static final String GREEN   = "\u001b[32m";
    private static final String YELLOW  = "\u001b[33m";
    private static final String RED     = "\u001b[31m";
    private static final String GRAY    = "\u001b[90m";
    private static final String RESET   = "\u001b[0m";

    public static void info(String msg) {
        System.out.println(GREEN + msg + RESET);
    }

    public static void ok(String msg) {
        System.out.println(GREEN + "[OK] " + msg + RESET);
    }

    public static void warn(String msg) {
        System.out.println(YELLOW + msg + RESET);
    }

    public static void error(String msg) {
        System.err.println(RED + "[ERROR] " + msg + RESET);
    }

    public static void proxy(String msg) {
        System.out.println(CYAN + "[PROXY] " + RESET + msg);
    }

    public static void pass(String msg) {
        System.out.println(GRAY + "[PASS]  " + RESET + msg);
    }

    public static void frag(String msg) {
        System.out.println(YELLOW + "[FRAG]  " + RESET + msg);
    }

    public static void debug(boolean verbose, String msg) {
        if (verbose) {
            System.out.println(GRAY + "[DEBUG] " + msg + RESET);
        }
    }

    public static void separator() {
        System.out.println(GRAY + "\u2500".repeat(65) + RESET);
    }

    public static void banner() {
        System.out.println();
        System.out.println(CYAN + "\u2554" + "\u2550".repeat(56) + "\u2557" + RESET);
        System.out.println(CYAN + "\u2551    NET-UNBLOCK v1.0.0 - DNS + SNI Bypass (AI+Steam)   \u2551" + RESET);
        System.out.println(CYAN + "\u255a" + "\u2550".repeat(56) + "\u255d" + RESET);
        System.out.println();
    }
}
