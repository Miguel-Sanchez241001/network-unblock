package com.steamunblock.tls;

/**
 * Parses a TLS ClientHello to find the byte offset of the SNI (Server Name Indication)
 * extension within the raw packet data.
 *
 * TLS Record layout:
 *   [0]     ContentType   (0x16 = Handshake)
 *   [1-2]   TLS Version
 *   [3-4]   Record Length
 *   [5]     HandshakeType (0x01 = ClientHello)
 *   [6-8]   Handshake Length
 *   [9-10]  ClientHello Version
 *   [11-42] Random (32 bytes)
 *   [43]    SessionID length + SessionID data
 *   ...     CipherSuites (2-byte length prefix)
 *   ...     CompressionMethods (1-byte length prefix)
 *   ...     Extensions (2-byte total length)
 *           SNI extension type = 0x0000
 */
public final class ClientHelloParser {

    private ClientHelloParser() {}

    /**
     * Finds the absolute byte offset at the midpoint of the server name within
     * the SNI extension. Returns -1 if the SNI is not found or the data is not
     * a valid TLS ClientHello.
     */
    public static int findSniOffset(byte[] data) {
        // Need at least TLS record header (5 bytes) + handshake header (4 bytes)
        if (data.length < 9 || u(data[0]) != 0x16) return -1;
        // Handshake type must be ClientHello (0x01)
        if (u(data[5]) != 0x01) return -1;

        int offset = 9;  // past TLS record header + handshake header
        offset += 2;     // ClientHello version (2 bytes)
        offset += 32;    // Random (32 bytes)
        if (offset >= data.length) return -1;

        // Session ID: 1 byte length + variable data
        int sessionIdLen = u(data[offset]);
        offset += 1 + sessionIdLen;
        if (offset + 2 >= data.length) return -1;

        // Cipher Suites: 2 byte length + variable data
        int cipherLen = (u(data[offset]) << 8) | u(data[offset + 1]);
        offset += 2 + cipherLen;
        if (offset + 1 >= data.length) return -1;

        // Compression Methods: 1 byte length + variable data
        int compLen = u(data[offset]);
        offset += 1 + compLen;
        if (offset + 2 >= data.length) return -1;

        // Extensions: 2 byte total length
        int extTotalLen = (u(data[offset]) << 8) | u(data[offset + 1]);
        offset += 2;
        int extEnd = offset + extTotalLen;

        // Iterate extensions looking for SNI (type 0x0000)
        while (offset + 4 < extEnd && offset + 4 < data.length) {
            int extType = (u(data[offset]) << 8) | u(data[offset + 1]);
            int extLen = (u(data[offset + 2]) << 8) | u(data[offset + 3]);

            if (extType == 0x0000) {
                // SNI extension found
                // offset+4 = SNI list length (2 bytes)
                // offset+6 = SNI type (1 byte, 0x00 = host_name)
                // offset+7 = name length (2 bytes)
                // offset+9 = start of server name
                int nameStart = offset + 9;
                if (nameStart < data.length && offset + 8 < data.length) {
                    int nameLen = (u(data[offset + 7]) << 8) | u(data[offset + 8]);
                    return nameStart + nameLen / 2; // midpoint of server name
                }
                return offset + 4; // fallback: start of SNI data
            }
            offset += 4 + extLen;
        }

        return -1; // SNI not found
    }

    /** Treat a Java signed byte as unsigned (0-255). */
    private static int u(byte b) {
        return b & 0xFF;
    }
}
