package ru.bitcoin.node.rvn.chain.assets;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetRuleKey.Kind.*;

class RvnRestrictedRuleChecksTest {
    private static final String ADDRESS = "ab".repeat(20);
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
        var change = RvnRestrictedRuleChecks.toggle(snapshot(Map.of()), key, 1);
        assertTrue(change.before().isEmpty()); assertEquals(Optional.of("1"), change.after());
        assertThrows(SecurityException.class, () -> RvnRestrictedRuleChecks.toggle(snapshot(Map.of()), key, 0));
        assertThrows(SecurityException.class, () -> RvnRestrictedRuleChecks.toggle(snapshot(Map.of(key, "1")), key, 1));
        assertThrows(SecurityException.class, () -> RvnRestrictedRuleChecks.toggle(snapshot(Map.of()), key, 2));
        assertTrue(RvnRestrictedRuleChecks.toggle(snapshot(Map.of(key, "1")), key, 0).after().isEmpty());
    }
}
