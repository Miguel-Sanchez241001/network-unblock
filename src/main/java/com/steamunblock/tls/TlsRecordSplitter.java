package com.steamunblock.tls;

import com.steamunblock.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;

/**
 * Splits a TLS ClientHello into two valid TLS records so that the SNI
 * field ends up in the second record. DPI firewalls typically only inspect
 * the first TLS record and won't find the SNI, allowing the connection.
 *
 * Per RFC 8446 Section 5.1, a handshake message MAY be fragmented
 * over several TLS records. The server MUST reassemble them.
 */
public class TlsRecordSplitter {

    private final int splitDelayMs;

    public TlsRecordSplitter(int splitDelayMs) {
        this.splitDelayMs = splitDelayMs;
    }

    /**
     * Splits the TLS ClientHello data and sends the two resulting records
     * as separate TCP segments through the server socket.
     */
    public void splitAndSend(Socket serverSocket, byte[] data) throws IOException {
        serverSocket.setTcpNoDelay(true);
        OutputStream out = serverSocket.getOutputStream();

        // Verify TLS Handshake record (ContentType 0x16)
        if (data.length < 10 || (data[0] & 0xFF) != 0x16) {
            Log.debug(true, "  Not a TLS handshake, sending as-is (" + data.length + " bytes)");
            out.write(data);
            out.flush();
            return;
        }

        int version = ((data[1] & 0xFF) << 8) | (data[2] & 0xFF);
        int recordLen = ((data[3] & 0xFF) << 8) | (data[4] & 0xFF);

        // Extract payload (everything after the 5-byte TLS record header)
        int payloadLen = Math.min(recordLen, data.length - 5);
        byte[] payload = new byte[payloadLen];
        System.arraycopy(data, 5, payload, 0, payloadLen);

        // Any trailing data after the TLS record
        int recordEnd = 5 + recordLen;
        byte[] trailing = (recordEnd < data.length)
                ? Arrays.copyOfRange(data, recordEnd, data.length)
                : new byte[0];

        // Find SNI offset to determine split point
        int sniOffsetAbsolute = ClientHelloParser.findSniOffset(data);
        int splitPoint;

        if (sniOffsetAbsolute > 5 && sniOffsetAbsolute < recordEnd) {
            // Split 10 bytes before the SNI
            splitPoint = sniOffsetAbsolute - 5 - 10;
            if (splitPoint < 1) splitPoint = 1;
            Log.frag("TLS Record Split: SNI@" + sniOffsetAbsolute + ", split payload@" + splitPoint);
        } else {
            // Fallback: split early in the payload
            splitPoint = Math.min(50, payload.length / 4);
            Log.frag("TLS Record Split: generic@" + splitPoint);
        }

        // Build TLS Record 1 (does NOT contain SNI)
        byte[] record1 = buildTlsRecord(0x16, version, payload, 0, splitPoint);

        // Build TLS Record 2 (contains SNI)
        byte[] record2 = buildTlsRecord(0x16, version, payload, splitPoint, payload.length - splitPoint);

        Log.frag("  -> Record1: " + record1.length + "B (header+" + splitPoint
                + ") | Record2: " + record2.length + "B (header+" + (payload.length - splitPoint) + ")");

        // Send as separate TCP segments with delay
        out.write(record1);
        out.flush();

        try {
            Thread.sleep(splitDelayMs);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }

        out.write(record2);
        if (trailing.length > 0) {
            out.write(trailing);
        }
        out.flush();

        serverSocket.setTcpNoDelay(false); // Restore Nagle
    }

    private byte[] buildTlsRecord(int contentType, int version, byte[] payload, int offset, int length) {
        byte[] record = new byte[5 + length];
        record[0] = (byte) contentType;
        record[1] = (byte) ((version >> 8) & 0xFF);
        record[2] = (byte) (version & 0xFF);
        record[3] = (byte) ((length >> 8) & 0xFF);
        record[4] = (byte) (length & 0xFF);
        System.arraycopy(payload, offset, record, 5, length);
        return record;
    }
}
