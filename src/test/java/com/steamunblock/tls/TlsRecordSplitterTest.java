package com.steamunblock.tls;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TlsRecordSplitterTest {

    /**
     * Builds a minimal TLS ClientHello for testing the splitter.
     */
    private byte[] buildClientHello(String serverName) {
        byte[] name = serverName.getBytes();
        int sniExtLen = 2 + 1 + 2 + name.length;
        int sniTotalLen = sniExtLen;
        int extBlockLen = 2 + 2 + sniTotalLen;
        int chBodyLen = 2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + extBlockLen;
        int hsLen = 1 + 3 + chBodyLen;
        byte[] data = new byte[5 + hsLen];
        int i = 0;

        data[i++] = 0x16;
        data[i++] = 0x03;
        data[i++] = 0x01;
        data[i++] = (byte) ((hsLen >> 8) & 0xFF);
        data[i++] = (byte) (hsLen & 0xFF);
        data[i++] = 0x01;
        data[i++] = 0x00;
        data[i++] = (byte) ((chBodyLen >> 8) & 0xFF);
        data[i++] = (byte) (chBodyLen & 0xFF);
        data[i++] = 0x03;
        data[i++] = 0x03;
        for (int r = 0; r < 32; r++) data[i++] = 0x00;
        data[i++] = 0x00;
        data[i++] = 0x00;
        data[i++] = 0x02;
        data[i++] = 0x00;
        data[i++] = 0x2F;
        data[i++] = 0x01;
        data[i++] = 0x00;
        data[i++] = (byte) ((extBlockLen >> 8) & 0xFF);
        data[i++] = (byte) (extBlockLen & 0xFF);
        data[i++] = 0x00;
        data[i++] = 0x00;
        data[i++] = (byte) ((sniTotalLen >> 8) & 0xFF);
        data[i++] = (byte) (sniTotalLen & 0xFF);
        int sniListLen = 1 + 2 + name.length;
        data[i++] = (byte) ((sniListLen >> 8) & 0xFF);
        data[i++] = (byte) (sniListLen & 0xFF);
        data[i++] = 0x00;
        data[i++] = (byte) ((name.length >> 8) & 0xFF);
        data[i++] = (byte) (name.length & 0xFF);
        System.arraycopy(name, 0, data, i, name.length);
        return data;
    }

    @Test
    void splitProducesTwoValidTlsRecords() throws IOException {
        byte[] hello = buildClientHello("store.steampowered.com");
        TlsRecordSplitter splitter = new TlsRecordSplitter(0); // no delay for test

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.getOutputStream()).thenReturn(captured);

        splitter.splitAndSend(mockSocket, hello);

        byte[] output = captured.toByteArray();
        assertTrue(output.length > 10, "Output should contain at least two TLS records");

        // First record should start with 0x16 (Handshake)
        assertEquals(0x16, output[0] & 0xFF, "First record should be TLS Handshake");

        // Parse first record length
        int record1Len = ((output[3] & 0xFF) << 8) | (output[4] & 0xFF);
        int record2Start = 5 + record1Len;

        // Second record should also start with 0x16
        assertTrue(record2Start < output.length, "Second record should exist");
        assertEquals(0x16, output[record2Start] & 0xFF, "Second record should be TLS Handshake");

        // Both records together should equal original payload
        int record2Len = ((output[record2Start + 3] & 0xFF) << 8) | (output[record2Start + 4] & 0xFF);
        int originalPayloadLen = ((hello[3] & 0xFF) << 8) | (hello[4] & 0xFF);
        assertEquals(originalPayloadLen, record1Len + record2Len,
                "Split records should contain same total payload");
    }

    @Test
    void nonTlsDataPassedThrough() throws IOException {
        byte[] notTls = "HELLO WORLD".getBytes();
        TlsRecordSplitter splitter = new TlsRecordSplitter(0);

        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Socket mockSocket = mock(Socket.class);
        when(mockSocket.getOutputStream()).thenReturn(captured);

        splitter.splitAndSend(mockSocket, notTls);

        assertArrayEquals(notTls, captured.toByteArray(),
                "Non-TLS data should pass through unchanged");
    }
}
