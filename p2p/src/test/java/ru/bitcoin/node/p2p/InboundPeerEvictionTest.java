package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class InboundPeerEvictionTest {
    @TestFactory List<DynamicTest> matchesUnchangedCoreCppOracle() throws Exception {
        String fixtures;
        try (var stream = getClass().getResourceAsStream("/core32-reference/eviction-vectors.tsv")) {
            assertNotNull(stream); fixtures = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        var rows = fixtures.lines().filter(line -> !line.startsWith("#")).toList();
        assertEquals(192, rows.size());
        var tests = new ArrayList<DynamicTest>();
        for (var row : rows) tests.add(DynamicTest.dynamicTest("Core eviction " + row.split("\t")[0], () -> {
            String[] fields = row.split("\t", -1);
            var candidates = new ArrayList<InboundPeerEviction.Candidate>();
            if (!fields[3].isEmpty()) for (var encoded : fields[3].split(";")) candidates.add(parse(encoded));
            if (fields[1].equals("0")) {
                var actual = InboundPeerEviction.select(candidates);
                assertEquals(fields[2], actual.isPresent() ? Long.toString(actual.getAsLong()) : "-");
            } else {
                InboundPeerEviction.protectByRatio(candidates);
                String actual = candidates.stream().map(InboundPeerEviction.Candidate::id).sorted().map(Object::toString)
                        .collect(java.util.stream.Collectors.joining(","));
                assertEquals(fields[2], actual.isEmpty() ? "-" : actual);
            }
        }));
        return tests;
    }

    @Test void protectsOutboundAndNoBanPeersBeforeOtherPreferences() {
        var candidates = new ArrayList<InboundPeerEviction.Candidate>();
        for (int i = 0; i < 100; i++) candidates.add(candidate(i, i, true, i % 2 == 0, i % 2 == 0));
        assertTrue(InboundPeerEviction.select(candidates).isEmpty());
    }

    @Test void preservesSmallProtectedPoolsAndChoosesOverrepresentedNetworkGroup() {
        var candidates = new ArrayList<InboundPeerEviction.Candidate>();
        for (int i = 0; i < 20; i++) candidates.add(candidate(i, i, false, false, true));
        assertTrue(InboundPeerEviction.select(candidates).isEmpty());
        candidates.clear();
        for (int i = 0; i < 100; i++) candidates.add(candidate(i, i < 80 ? 1 : i, false, false, true));
        assertEquals(79, InboundPeerEviction.select(candidates).orElseThrow());
    }

    @Test void preferenceCannotOverrideProtectedPeers() {
        var candidates = new ArrayList<InboundPeerEviction.Candidate>();
        for (int i = 0; i < 100; i++) candidates.add(candidate(i, i, i == 1, false, true));
        assertEquals(87, InboundPeerEviction.select(candidates).orElseThrow());
        candidates.set(64, candidate(64, 64, true, false, true));
        assertEquals(64, InboundPeerEviction.select(candidates).orElseThrow());
    }

    @Test void ratioProtectsOldConnectionsAndNewPrivacyPeers() {
        var candidates = new ArrayList<InboundPeerEviction.Candidate>();
        for (int i = 0; i < 12; i++) candidates.add(new InboundPeerEviction.Candidate(i, i, i, i, i,
                false, true, false, i, false, false,
                i >= 10 ? InboundPeerEviction.Network.ONION : InboundPeerEviction.Network.IPV4, false, true));
        InboundPeerEviction.protectByRatio(candidates);
        assertEquals(List.of(4L,5L,6L,7L,8L,9L), candidates.stream().map(InboundPeerEviction.Candidate::id).sorted().toList());
    }

    private static InboundPeerEviction.Candidate candidate(long id, long group, boolean prefer, boolean noBan, boolean inbound) {
        return new InboundPeerEviction.Candidate(id, id, id * 1_000_000, id, id,
                false, true, false, group, prefer, false, InboundPeerEviction.Network.IPV4, noBan, inbound);
    }

    private static InboundPeerEviction.Candidate parse(String encoded) {
        String[] v = encoded.split(",");
        return new InboundPeerEviction.Candidate(Long.parseLong(v[0]), Long.parseLong(v[1]), Long.parseLong(v[2]),
                Long.parseLong(v[3]), Long.parseLong(v[4]), bit(v[5]), bit(v[6]), bit(v[7]), Long.parseUnsignedLong(v[8]),
                bit(v[9]), bit(v[10]), InboundPeerEviction.Network.values()[Integer.parseInt(v[11])], bit(v[12]), bit(v[13]));
    }
    private static boolean bit(String value) { return value.equals("1"); }
}
