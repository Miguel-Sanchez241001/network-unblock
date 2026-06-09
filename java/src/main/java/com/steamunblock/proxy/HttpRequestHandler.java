package com.steamunblock.proxy;

import com.steamunblock.dns.DohResolver;
import com.steamunblock.dns.SteamDomains;
import com.steamunblock.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles plain HTTP requests (GET, POST, etc.) by forwarding them to the
 * target server. For Steam domains, resolves the IP via DoH.
 */
public class HttpRequestHandler {

    private final SteamDomains domains;
    private final DohResolver dohResolver;
    private final boolean verbose;

    public HttpRequestHandler(SteamDomains domains, DohResolver dohResolver, boolean verbose) {
        this.domains = domains;
        this.dohResolver = dohResolver;
        this.verbose = verbose;
    }

    public void handle(Socket clientSocket, InputStream clientIn, String requestLine) {
        try {
            // Parse "GET http://hostname:port/path HTTP/1.1"
            String[] parts = requestLine.split(" ");
            if (parts.length < 3) {
                sendError(clientSocket, 400, "Bad Request");
                return;
            }

            String method = parts[0];
            URI uri = URI.create(parts[1]);
            String httpVersion = parts[2].trim();

            String hostname = uri.getHost();
            int port = uri.getPort() > 0 ? uri.getPort() : 80;
            String path = uri.getRawPath();
            if (uri.getRawQuery() != null) {
                path += "?" + uri.getRawQuery();
            }
            if (path.isEmpty()) path = "/";

            // Read remaining headers from client
            List<String> headers = readHeaders(clientIn);

            // Resolve hostname
            boolean isSteam = domains.isSteamDomain(hostname);
            String targetIp = hostname;

            if (isSteam) {
                String ip = domains.getIp(hostname);
                if (ip != null) {
                    targetIp = ip;
                } else {
                    String resolved = dohResolver.resolve(hostname);
                    if (resolved != null) {
                        targetIp = resolved;
                        domains.cacheResolution(hostname, List.of(resolved));
                    }
                }
                Log.proxy("HTTP " + method + " " + hostname + " -> " + targetIp + ":" + port);
            } else {
                Log.pass("HTTP " + method + " " + hostname + " -> " + targetIp + ":" + port);
            }

            // Connect to target
            try (Socket serverSocket = new Socket()) {
                serverSocket.connect(new InetSocketAddress(targetIp, port), 15_000);
                serverSocket.setSoTimeout(15_000);

                OutputStream serverOut = serverSocket.getOutputStream();
                InputStream serverIn = serverSocket.getInputStream();

                // Send request line with path only (not full URL)
                serverOut.write((method + " " + path + " " + httpVersion + "\r\n").getBytes(StandardCharsets.UTF_8));

                // Forward headers, replacing Host with original hostname
                boolean hostSent = false;
                for (String header : headers) {
                    if (header.toLowerCase().startsWith("host:")) {
                        serverOut.write(("Host: " + hostname + (port != 80 ? ":" + port : "") + "\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                        hostSent = true;
                    } else {
                        serverOut.write((header + "\r\n").getBytes(StandardCharsets.UTF_8));
                    }
                }
                if (!hostSent) {
                    serverOut.write(("Host: " + hostname + "\r\n").getBytes(StandardCharsets.UTF_8));
                }
                serverOut.write("\r\n".getBytes());
                serverOut.flush();

                // Relay response back to client
                OutputStream clientOut = clientSocket.getOutputStream();
                serverIn.transferTo(clientOut);
                clientOut.flush();
            }

        } catch (Exception e) {
            Log.error("HTTP: " + e.getMessage());
            sendError(clientSocket, 502, "Bad Gateway");
        }
    }

    private List<String> readHeaders(InputStream in) throws IOException {
        List<String> headers = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int prev = 0, curr;
        while ((curr = in.read()) != -1) {
            if (curr == '\n') {
                String headerLine = line.toString().replace("\r", "");
                if (headerLine.isEmpty()) break; // end of headers
                headers.add(headerLine);
                line.setLength(0);
            } else {
                line.append((char) curr);
            }
        }
        return headers;
    }

    private void sendError(Socket clientSocket, int code, String message) {
        try {
            String response = "HTTP/1.1 " + code + " " + message + "\r\n"
                    + "Content-Length: 0\r\n\r\n";
            clientSocket.getOutputStream().write(response.getBytes());
        } catch (IOException ignored) {}
    }
}
