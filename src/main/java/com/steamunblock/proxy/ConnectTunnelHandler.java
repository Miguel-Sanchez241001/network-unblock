package com.steamunblock.proxy;

import com.steamunblock.dns.DohResolver;
import com.steamunblock.dns.SteamDomains;
import com.steamunblock.tls.TlsRecordSplitter;
import com.steamunblock.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * Handles HTTPS CONNECT tunnels.
 * For Steam domains: resolves via DoH and applies TLS Record Splitting.
 * For other domains: creates a transparent TCP tunnel.
 */
public class ConnectTunnelHandler {

    private final SteamDomains domains;
    private final DohResolver dohResolver;
    private final TlsRecordSplitter splitter;
    private final boolean verbose;

    public ConnectTunnelHandler(SteamDomains domains, DohResolver dohResolver,
                                TlsRecordSplitter splitter, boolean verbose) {
        this.domains = domains;
        this.dohResolver = dohResolver;
        this.splitter = splitter;
        this.verbose = verbose;
    }

    public void handle(Socket clientSocket, InputStream clientIn, String connectLine) {
        // Parse "CONNECT hostname:port HTTP/1.1"
        String target = connectLine.split(" ")[1];
        String[] parts = target.split(":");
        String hostname = parts[0];
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : 443;

        // Consume remaining HTTP headers
        try {
            consumeHeaders(clientIn);
        } catch (IOException e) {
            Log.debug(verbose, "Failed to read CONNECT headers: " + e.getMessage());
            return;
        }

        // Resolve hostname
        boolean isSteam = domains.isSteamDomain(hostname);
        String targetIp = resolveTarget(hostname, isSteam);

        String tag = isSteam ? "STEAM" : "PASS";
        if (isSteam) {
            Log.proxy("CONNECT " + hostname + ":" + port + " -> " + targetIp + ":" + port);
        } else {
            Log.pass("CONNECT " + hostname + ":" + port + " -> " + targetIp + ":" + port);
        }

        Socket serverSocket = new Socket();
        try {
            serverSocket.setTcpNoDelay(true);
            serverSocket.connect(new InetSocketAddress(targetIp, port), 30_000);

            // Send "200 Connection Established" to client
            OutputStream clientOut = clientSocket.getOutputStream();
            clientOut.write(("HTTP/1.1 200 Connection Established\r\nProxy-Agent: SteamUnblock\r\n\r\n")
                    .getBytes());
            clientOut.flush();

            clientSocket.setTcpNoDelay(true);

            // If Steam domain and splitting enabled, intercept first packet (ClientHello)
            if (isSteam && splitter != null) {
                byte[] firstPacket = readAvailable(clientIn, 16384);
                if (firstPacket.length > 0) {
                    Log.frag("TLS ClientHello " + hostname + " (" + firstPacket.length + " bytes)");
                    splitter.splitAndSend(serverSocket, firstPacket);
                }
            }

            // Bidirectional relay
            ConnectionPiper.pipe(clientSocket, serverSocket);

        } catch (IOException e) {
            Log.error("CONNECT " + hostname + ": " + e.getMessage());
            try {
                if (!clientSocket.isClosed()) {
                    clientSocket.getOutputStream().write(
                            "HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes());
                }
            } catch (IOException ignored) {}
        } finally {
            try { serverSocket.close(); } catch (IOException ignored) {}
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    private String resolveTarget(String hostname, boolean isSteam) {
        if (isSteam) {
            // Check hardcoded map first
            String ip = domains.getIp(hostname);
            if (ip != null) return ip;

            // Try DoH resolution
            List<String> ips = dohResolver.resolveAll(hostname);
            if (!ips.isEmpty()) {
                domains.cacheResolution(hostname, ips);
                Log.proxy("DoH: " + hostname + " -> " + String.join(", ", ips));
                return ips.getFirst();
            }
        }
        // Non-Steam or resolution failed: use hostname (system DNS)
        return hostname;
    }

    private void consumeHeaders(InputStream in) throws IOException {
        // Read until we find empty line (\r\n\r\n)
        int prev = 0, curr;
        int newlines = 0;
        while ((curr = in.read()) != -1) {
            if (curr == '\n') {
                newlines++;
                if (newlines >= 2) break; // found \r\n\r\n or \n\n
            } else if (curr != '\r') {
                newlines = 0;
            }
        }
    }

    private byte[] readAvailable(InputStream in, int maxSize) throws IOException {
        // Wait briefly for data to arrive
        int timeout = 0;
        while (in.available() == 0 && timeout < 1000) {
            try { Thread.sleep(1); } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                break;
            }
            timeout++;
        }
        int available = Math.min(in.available(), maxSize);
        if (available <= 0) return new byte[0];
        byte[] buf = new byte[available];
        int read = in.read(buf, 0, available);
        if (read <= 0) return new byte[0];
        if (read < buf.length) {
            byte[] trimmed = new byte[read];
            System.arraycopy(buf, 0, trimmed, 0, read);
            return trimmed;
        }
        return buf;
    }
}
