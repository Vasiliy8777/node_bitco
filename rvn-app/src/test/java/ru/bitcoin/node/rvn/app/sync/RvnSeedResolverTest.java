package ru.bitcoin.node.rvn.app.sync;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RvnSeedResolverTest {
    private static final String ANSWER = """
        {"Status":0,"TC":false,"Question":[{"name":"seed.example.","type":1}],
         "Answer":[{"name":"seed.example.","type":1,"TTL":300,"data":"203.0.113.7"},
                   {"name":"seed.example.","type":1,"TTL":300,"data":"203.0.113.8"},
                   {"name":"seed.example.","type":1,"TTL":300,"data":"203.0.113.7"}]}
        """;
    @Test void brokenSystemDnsFallsBackAndCachesAllUniqueAddresses() throws Exception {
        var system = new AtomicInteger(); var https = new AtomicInteger();
        try (var resolver = new RvnSeedResolver(host -> { system.incrementAndGet(); throw new IOException("Bad DNS packet"); },
                host -> { https.incrementAndGet(); return RvnSeedResolver.parse(host, ANSWER); })) {
            assertEquals(Set.of("203.0.113.7", "203.0.113.8"), Set.copyOf(resolver.resolve("seed.example")));
            resolver.resolve("seed.example");
            assertEquals(1, system.get()); assertEquals(1, https.get());
            assertEquals(List.of("127.0.0.1"), resolver.resolve("127.0.0.1"));
            assertEquals(1, system.get());
        }
    }
    @Test void goodSystemDnsDoesNotUseHttps() throws Exception {
        try (var resolver = new RvnSeedResolver(h -> List.of("203.0.113.7"), h -> { fail("Unexpected HTTPS fallback"); return List.of(); })) {
            assertEquals(List.of("203.0.113.7"), resolver.resolve("seed.example"));
        }
    }
    @Test void mismatchedTruncatedFailedAndNonLiteralAnswersAreRejected() {
        assertThrows(IOException.class, () -> RvnSeedResolver.parse("other.example", ANSWER));
        assertThrows(IOException.class, () -> RvnSeedResolver.parse("seed.example", ANSWER.replace("\"TC\":false", "\"TC\":true")));
        assertThrows(IOException.class, () -> RvnSeedResolver.parse("seed.example", ANSWER.replace("\"Status\":0", "\"Status\":2")));
        assertThrows(IOException.class, () -> RvnSeedResolver.parse("seed.example", ANSWER.replace("203.0.113.7", "attacker.example")));
        assertThrows(IOException.class, () -> RvnSeedResolver.parse("seed.example", ANSWER.replace("203.0.113.7", "999.0.0.1")));
        assertThrows(IOException.class, () -> RvnSeedResolver.parse("seed.example", " ".repeat(65537)));
        assertThrows(IOException.class, () -> RvnSeedResolver.parse("seed.example", "{\"Status\":"));
    }
    @Test void disabledFallbackPreservesFailureAndInterruptedDnsDoesNotContactHttps() {
        try (var resolver = new RvnSeedResolver(h -> { throw new IOException("DNS unavailable"); }, null)) {
            assertThrows(IOException.class, () -> resolver.resolve("seed.example"));
        }
        try (var resolver = new RvnSeedResolver(h -> {
            Thread.currentThread().interrupt(); throw new InterruptedIOException("Cancelled");
        }, h -> { fail("Cancellation must not start HTTPS lookup"); return List.of(); })) {
            assertThrows(InterruptedIOException.class, () -> resolver.resolve("seed.example"));
        } finally { Thread.interrupted(); }
    }
    @Test void closedResolverRejectsCachedAndLiteralLookups() throws Exception {
        var resolver = new RvnSeedResolver(h -> List.of("203.0.113.7"), null);
        resolver.resolve("seed.example"); resolver.close(); resolver.close();
        assertThrows(IOException.class, () -> resolver.resolve("seed.example"));
        assertThrows(IOException.class, () -> resolver.resolve("127.0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> new RvnSeedResolver("http://dns.google/resolve"));
    }
}
