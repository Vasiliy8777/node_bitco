package ru.bitcoin.node.rvn.chain.assets;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetRuleKey.Kind.*;

class RvnRestrictedRuleChecksTest {
    private static final String ADDRESS = "ab".repeat(20);
    @Test void durableParentMembershipMatchesExecutedCoreFlushedVectors() throws Exception {
        var resource = getClass().getResourceAsStream("/core-reference/qualifier-vectors.tsv");
        assertNotNull(resource);
        var expected = new HashMap<String, String[]>();
        try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(resource, java.nio.charset.StandardCharsets.UTF_8))) {
            reader.lines().filter(line -> !line.startsWith("#")).forEach(line -> {
                var fields = line.split("\t"); expected.put(fields[0], fields);
            });
        }
        var child = new RvnAssetRuleKey(QUALIFIER, "#KYC/#ID", ADDRESS);
        var root = new RvnAssetRuleKey(QUALIFIER, "#KYC", ADDRESS);
        var state = new HashMap<RvnAssetRuleKey, String>();
        for (String stage : List.of("empty", "child-flushed", "root-remove-flushed")) {
            if (stage.equals("child-flushed")) state.put(child, "1");
            if (stage.equals("root-remove-flushed")) {
                assertTrue(RvnRestrictedRuleChecks.toggle(snapshot(state), root, 0).isEmpty()); state.remove(root);
            }
            var fields = expected.get(stage); assertNotNull(fields);
            assertEquals(fields[1].equals("1"), snapshot(state).hasQualifier("#KYC", ADDRESS), stage);
            assertEquals(fields[2].equals("1"), snapshot(state).hasQualifier("#KYC/#ID", ADDRESS), stage);
        }
        // The executed native vectors expose why a dirty cache must not be used
        // as the Java durable parent, or treated as a universal consensus oracle.
        assertNotEquals(expected.get("root-remove-dirty")[1], expected.get("root-remove-flushed")[1]);
    }
    private static RvnAssetRulesView snapshot(Map<RvnAssetRuleKey, String> map) {
        var stable = Map.copyOf(map);
        return new RvnAssetRulesView() {
            public Optional<String> findRule(RvnAssetRuleKey key) { return Optional.ofNullable(stable.get(key)); }
            public boolean hasQualifier(String name, String address) {
                return stable.keySet().stream().anyMatch(k -> k.kind() == QUALIFIER && k.addressHash().equals(address)
                        && (k.name().equals(name) || k.name().startsWith(name + "/")));
            }
        };
    }
    @Test void verifierPrecedenceNegationParenthesesAndQualifierExistenceAreChecked() {
        var expr = RvnRestrictedVerifier.fromOutput("KYC|AML&!BANNED");
        assertEquals(Set.of("#KYC", "#AML", "#BANNED"), expr.qualifiers());
        assertTrue(expr.evaluate(q -> q.equals("#KYC")));
        assertFalse(expr.evaluate(q -> q.equals("#AML") || q.equals("#BANNED")));
        assertTrue(RvnRestrictedVerifier.fromOutput("(KYC|AML)&!BANNED").evaluate(q -> q.equals("#AML")));
        assertTrue(RvnRestrictedVerifier.fromOutput("true").evaluate(q -> false));
        for (String bad : List.of("", "true ", "false", "1", "#KYC", "KYC&&AML", "KYC|", "(KYC", "KYC)", "KYC/AML", "KYC!AML"))
            assertThrows(SecurityException.class, () -> RvnRestrictedVerifier.fromOutput(bad), bad);
        assertThrows(SecurityException.class, () -> RvnRestrictedVerifier.fromOutput("(".repeat(40) + "KYC" + ")".repeat(40)));
        assertThrows(SecurityException.class, () -> RvnRestrictedRuleChecks.requireVerifier(snapshot(Map.of()), "KYC|AML", null, q -> q.equals("#KYC")));
    }
    @Test void transfersUseParentSnapshotIncludingInheritedQualifierAndFreezeFlags() {
        var data = new HashMap<RvnAssetRuleKey, String>();
        data.put(new RvnAssetRuleKey(QUALIFIER, "#KYC/#ID", ADDRESS), "1");
        data.put(new RvnAssetRuleKey(VERIFIER, "$TOKEN", ""), "KYC");
        var parent = snapshot(data);
        assertDoesNotThrow(() -> RvnRestrictedRuleChecks.requireDestination(parent, "$TOKEN", ADDRESS, q -> true));
        data.remove(new RvnAssetRuleKey(QUALIFIER, "#KYC/#ID", ADDRESS));
        // An unrelated mutable overlay cannot change the parent snapshot's decision.
        assertDoesNotThrow(() -> RvnRestrictedRuleChecks.requireDestination(parent, "$TOKEN", ADDRESS, q -> true));
        assertThrows(SecurityException.class, () -> RvnRestrictedRuleChecks.requireDestination(snapshot(data), "$TOKEN", ADDRESS, q -> true));
        data.put(new RvnAssetRuleKey(GLOBAL_FREEZE, "$TOKEN", ""), "1");
        assertThrows(SecurityException.class, () -> RvnRestrictedRuleChecks.requireDestination(snapshot(data), "$TOKEN", ADDRESS, q -> true));
        data.put(new RvnAssetRuleKey(ADDRESS_FREEZE, "$TOKEN", ADDRESS), "1");
        assertThrows(SecurityException.class, () -> RvnRestrictedRuleChecks.requireSource(snapshot(data), "$TOKEN", ADDRESS));
    }
    @Test void togglesRejectInvalidAndRedundantChangesAndProduceExactUndoValues() {
        var key = new RvnAssetRuleKey(GLOBAL_FREEZE, "$TOKEN", "");
        var change = RvnRestrictedRuleChecks.toggle(snapshot(Map.of()), key, 1).orElseThrow();
        assertTrue(change.before().isEmpty()); assertEquals(Optional.of("1"), change.after());
        assertThrows(SecurityException.class, () -> RvnRestrictedRuleChecks.toggle(snapshot(Map.of()), key, 0));
        assertThrows(SecurityException.class, () -> RvnRestrictedRuleChecks.toggle(snapshot(Map.of(key, "1")), key, 1));
        assertThrows(SecurityException.class, () -> RvnRestrictedRuleChecks.toggle(snapshot(Map.of()), key, 2));
        assertTrue(RvnRestrictedRuleChecks.toggle(snapshot(Map.of(key, "1")), key, 0).orElseThrow().after().isEmpty());
    }

    @Test void inheritedRootRemovalIsAuthorizedButNeverDeletesTheChildOrInventsUndo() {
        var root = new RvnAssetRuleKey(QUALIFIER, "#KYC", ADDRESS);
        var child = new RvnAssetRuleKey(QUALIFIER, "#KYC/#ID", ADDRESS);
        var parent = snapshot(Map.of(child, "1"));
        assertTrue(parent.hasQualifier("#KYC", ADDRESS));
        assertTrue(RvnRestrictedRuleChecks.toggle(parent, root, 0).isEmpty());
        assertTrue(parent.findRule(child).isPresent());
        assertTrue(parent.hasQualifier("#KYC", ADDRESS));
        assertThrows(SecurityException.class, () -> RvnRestrictedRuleChecks.toggle(parent, root, 1));
    }
}
