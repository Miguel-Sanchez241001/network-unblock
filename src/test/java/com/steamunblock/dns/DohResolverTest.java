package com.steamunblock.dns;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DohResolverTest {

    @Test
    void resolvesKnownDomain() {
        DohResolver resolver = new DohResolver("https://dns.google/resolve");
        List<String> ips = resolver.resolveAll("dns.google");
        assertFalse(ips.isEmpty(), "Should resolve dns.google to at least one IP");
        // dns.google resolves to 8.8.8.8 or 8.8.4.4
        assertTrue(ips.stream().anyMatch(ip -> ip.startsWith("8.8.")),
                "dns.google should resolve to 8.8.x.x");
    }

    @Test
    void returnsEmptyForNonexistentDomain() {
        DohResolver resolver = new DohResolver("https://dns.google/resolve");
        List<String> ips = resolver.resolveAll("this-domain-does-not-exist-xyz123.invalid");
        assertTrue(ips.isEmpty(), "Non-existent domain should return empty list");
    }

    @Test
    void singleResolveReturnsFirstIp() {
        DohResolver resolver = new DohResolver("https://dns.google/resolve");
        String ip = resolver.resolve("dns.google");
        assertNotNull(ip, "Should return a non-null IP for dns.google");
        assertTrue(ip.matches("\\d+\\.\\d+\\.\\d+\\.\\d+"), "Should be a valid IPv4 address");
    }

    @Test
    void singleResolveReturnsNullForBadDomain() {
        DohResolver resolver = new DohResolver("https://dns.google/resolve");
        String ip = resolver.resolve("this-domain-does-not-exist-xyz123.invalid");
        assertNull(ip, "Should return null for non-existent domain");
    }
}
