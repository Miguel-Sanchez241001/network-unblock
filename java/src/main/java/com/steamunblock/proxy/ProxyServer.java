package com.steamunblock.proxy;

import com.steamunblock.dns.DohResolver;
import com.steamunblock.dns.SteamDomains;
import com.steamunblock.tls.TlsRecordSplitter;
import com.steamunblock.util.Log;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * HTTP proxy server that handles both plain HTTP requests and HTTPS CONNECT tunnels.
 * Uses Java 21 virtual threads for concurrent connection handling.
 */
public class ProxyServer implements Closeable {

    private final int port;
    private final String bindAddress;
    private final SteamDomains domains;
    private final DohResolver dohResolver;
    private final TlsRecordSplitter splitter;
    private final boolean verbose;

    private volatile boolean running;
    private ServerSocket serverSocket;

    public ProxyServer(int port, String bindAddress, SteamDomains domains,
                       DohResolver dohResolver, TlsRecordSplitter splitter, boolean verbose) {
        this.port = port;
        this.bindAddress = bindAddress;
        this.domains = domains;
        this.dohResolver = dohResolver;
        this.splitter = splitter;
        this.verbose = verbose;
    }

    public void start() throws IOException {
        serverSocket = new ServerSocket();
        serverSocket.setReuseAddress(true);
        serverSocket.bind(new InetSocketAddress(bindAddress, port));
        running = true;

        // Accept loop on a virtual thread
        Thread.ofVirtual().name("proxy-accept").start(this::acceptLoop);
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {}
    }

    @Override
    public void close() {
        stop();
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                // Each connection gets its own virtual thread
                Thread.ofVirtual()
                        .name("conn-" + clientSocket.getRemoteSocketAddress())
                        .start(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (running) {
                    Log.error("Accept error: " + e.getMessage());
                }
            }
        }
    }

    private void handleConnection(Socket clientSocket) {
        try {
            clientSocket.setSoTimeout(60_000);
            InputStream in = new BufferedInputStream(clientSocket.getInputStream());

            // Read the first line to determine HTTP method
            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isBlank()) {
                clientSocket.close();
                return;
            }

            Log.debug(verbose, "Request: " + requestLine.trim());

            if (requestLine.toUpperCase().startsWith("CONNECT ")) {
                new ConnectTunnelHandler(domains, dohResolver, splitter, verbose)
                        .handle(clientSocket, in, requestLine.trim());
            } else {
                new HttpRequestHandler(domains, dohResolver, verbose)
                        .handle(clientSocket, in, requestLine.trim());
            }
        } catch (Exception e) {
            Log.debug(verbose, "Connection error: " + e.getMessage());
        } finally {
            try { clientSocket.close(); } catch (IOException ignored) {}
        }
    }

    /**
     * Reads a single line from the input stream (up to \n or \r\n).
     */
    private String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                return sb.toString();
            }
            if (c != '\r') {
                sb.append((char) c);
            }
            if (sb.length() > 8192) break; // prevent DoS
        }
        return sb.isEmpty() ? null : sb.toString();
    }
}
