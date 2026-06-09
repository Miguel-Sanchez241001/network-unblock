package com.steamunblock.tls;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClientHelloParserTest {

    /**
     * Builds a minimal TLS ClientHello with a known SNI for testing.
     */
    private byte[] buildClientHello(String serverName) {
        byte[] name = serverName.getBytes();

        // SNI extension: type(2) + length(2) + list_length(2) + type(1) + name_length(2) + name
        int sniExtLen = 2 + 1 + 2 + name.length; // SNI list contents
        int sniTotalLen = sniExtLen;

        // Extensions block: SNI only
        int extBlockLen = 2 + 2 + sniTotalLen; // ext_type(2) + ext_len(2) + data

        // ClientHello body:
        // version(2) + random(32) + session_id_len(1) + cipher_suites_len(2) + 2 cipher(2)
        // + compression_len(1) + null_comp(1) + extensions_len(2) + extensions
        int chBodyLen = 2 + 32 + 1 + 2 + 2 + 1 + 1 + 2 + extBlockLen;

        // Handshake: type(1) + length(3) + body
        int hsLen = 1 + 3 + chBodyLen;

        // TLS Record: header(5) + handshake
        byte[] data = new byte[5 + hsLen];
        int i = 0;

        // TLS Record Header
        data[i++] = 0x16;       // ContentType: Handshake
        data[i++] = 0x03;       // Version major: TLS 1.0
        data[i++] = 0x01;       // Version minor
        int recordLen = hsLen;
        data[i++] = (byte) ((recordLen >> 8) & 0xFF);
        data[i++] = (byte) (recordLen & 0xFF);

        // Handshake header
        data[i++] = 0x01;       // HandshakeType: ClientHello
        data[i++] = 0x00;       // Length (3 bytes)
        data[i++] = (byte) ((chBodyLen >> 8) & 0xFF);
        data[i++] = (byte) (chBodyLen & 0xFF);

        // ClientHello version
        data[i++] = 0x03;
        data[i++] = 0x03;       // TLS 1.2

        // Random (32 bytes of zeros)
        for (int r = 0; r < 32; r++) data[i++] = 0x00;

        // Session ID (length 0)
        data[i++] = 0x00;

        // Cipher Suites (length 2, one cipher)
        data[i++] = 0x00;
        data[i++] = 0x02;
        data[i++] = 0x00;
        data[i++] = 0x2F;       // TLS_RSA_WITH_AES_128_CBC_SHA

        // Compression Methods (length 1, null)
        data[i++] = 0x01;
        data[i++] = 0x00;

        // Extensions length
        data[i++] = (byte) ((extBlockLen >> 8) & 0xFF);
        data[i++] = (byte) (extBlockLen & 0xFF);

        // SNI extension
        data[i++] = 0x00;       // Extension type: SNI (0x0000)
        data[i++] = 0x00;
        data[i++] = (byte) ((sniTotalLen >> 8) & 0xFF);
        data[i++] = (byte) (sniTotalLen & 0xFF);

        // SNI list length
        int sniListLen = 1 + 2 + name.length;
        data[i++] = (byte) ((sniListLen >> 8) & 0xFF);
        data[i++] = (byte) (sniListLen & 0xFF);

        // SNI entry: type host_name (0), name length, name
        data[i++] = 0x00;       // host_name
        data[i++] = (byte) ((name.length >> 8) & 0xFF);
        data[i++] = (byte) (name.length & 0xFF);
        System.arraycopy(name, 0, data, i, name.length);

        return data;
    }

    @Test
    void findsSniInMinimalClientHello() {
        byte[] hello = buildClientHello("store.steampowered.com");
        int offset = ClientHelloParser.findSniOffset(hello);
        assertTrue(offset > 0, "SNI offset should be positive");

        // The offset should point within the server name string
        String name = "store.steampowered.com";
        // Check that the offset is roughly in the middle of the name
        // (findSniOffset returns nameStart + nameLen/2)
        assertTrue(offset > 50, "SNI offset should be past the header fields");
    }

    @Test
    void returnsNegativeForNonTlsData() {
        byte[] notTls = "GET / HTTP/1.1\r\n".getBytes();
        assertEquals(-1, ClientHelloParser.findSniOffset(notTls));
    }

    @Test
    void returnsNegativeForEmptyData() {
        assertEquals(-1, ClientHelloParser.findSniOffset(new byte[0]));
    }

    @Test
    void returnsNegativeForTruncatedData() {
        byte[] truncated = new byte[]{0x16, 0x03, 0x01, 0x00};
        assertEquals(-1, ClientHelloParser.findSniOffset(truncated));
    }

    @Test
    void sniOffsetChangesWithDifferentNames() {
        byte[] short_ = buildClientHello("a.com");
        byte[] long_ = buildClientHello("very.long.domain.name.example.com");

        int offsetShort = ClientHelloParser.findSniOffset(short_);
        int offsetLong = ClientHelloParser.findSniOffset(long_);

        assertTrue(offsetShort > 0);
        assertTrue(offsetLong > 0);
        // Longer name should have a larger offset (midpoint is further)
        assertTrue(offsetLong > offsetShort,
                "Longer SNI should have larger midpoint offset");
    }
}
