package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UnspendableScriptTest {
    @Test void sizeLimitIsStrictlyGreaterThanTenThousand() {
        assertFalse(UnspendableScript.isUnspendable(new byte[10_000]));
        assertTrue(UnspendableScript.isUnspendable(new byte[10_001]));
    }
    @Test void onlyLeadingReturnIsPruned() {
        assertTrue(UnspendableScript.isUnspendable(new byte[]{0x6a}));
        assertTrue(UnspendableScript.isUnspendable(new byte[]{0x6a,0x4c}));
        assertFalse(UnspendableScript.isUnspendable(new byte[]{1,0x6a}));
        assertFalse(UnspendableScript.isUnspendable(new byte[]{0,0x63,0x6a,0x68,0x51}));
    }
    @Test void doesNotPruneOtherUnsatisfiableOrEmptyScripts() {
        assertFalse(UnspendableScript.isUnspendable(new byte[0]));
        assertFalse(UnspendableScript.isUnspendable(new byte[]{0}));
        assertFalse(UnspendableScript.isUnspendable(new byte[]{0x50}));
        assertThrows(NullPointerException.class,()->UnspendableScript.isUnspendable(null));
    }
}
