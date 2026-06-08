package com.steamunblock.proxy;

import java.io.IOException;
import java.net.Socket;

/**
 * Bidirectional byte relay between two sockets using virtual threads.
 * Each direction (client->server, server->client) runs in its own virtual thread.
 * Uses InputStream.transferTo() which blocks efficiently on virtual threads.
 */
public final class ConnectionPiper {

    private ConnectionPiper() {}

    /**
     * Pipes data bidirectionally between two sockets until either side closes.
     * Blocks until both directions are complete.
     */
    public static void pipe(Socket clientSocket, Socket serverSocket) {
        Thread clientToServer = Thread.ofVirtual().name("c->s").start(() -> {
            try {
                clientSocket.getInputStream().transferTo(serverSocket.getOutputStream());
            } catch (IOException ignored) {
                // Connection closed or reset
            } finally {
                closeQuietly(serverSocket);
            }
        });

        Thread serverToClient = Thread.ofVirtual().name("s->c").start(() -> {
            try {
                serverSocket.getInputStream().transferTo(clientSocket.getOutputStream());
            } catch (IOException ignored) {
                // Connection closed or reset
            } finally {
                closeQuietly(clientSocket);
            }
        });

        try {
            clientToServer.join();
            serverToClient.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            if (!socket.isClosed()) {
                socket.close();
            }
        } catch (IOException ignored) {}
    }
}
