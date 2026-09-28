package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.crypto.hash.Sha256;
import ru.bitcoin.node.protocol.transaction.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class MempoolTestAcceptTest {
    private static final byte[] SCRIPT = HexFormat.of().parseHex("0020" + HexFormat.of().formatHex(Sha256.hash(new byte[]{0x51})));
    private static final OutPoint FUND = new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0));
    private static final UtxoView COINS = out -> out.equals(FUND) ? Optional.of(new UtxoEntry(100_000, SCRIPT, 100, false)) : Optional.empty();
    private static final MempoolValidationContext CONTEXT = new MempoolValidationContext(200, 1_700_000_000, h -> 1_600_000_000);

    @Test
    void dryRunDoesNotMutatePoolOnSuccessOrFailure() {
        Mempool pool = new Mempool();
        Transaction tx = tx(FUND, 90_000);
        var accepted = pool.testAccept(List.of(tx), CONTEXT, COINS);
        assertTrue(accepted.allowed());
        assertEquals(1, accepted.entries().size());
        assertEquals(0, pool.size());

        Transaction missing = tx(new OutPoint(Hash256.fromDisplayHex("22".repeat(32)), new UInt32(0)), 1_000);
        var rejected = pool.testAccept(List.of(missing), CONTEXT, COINS);
        assertFalse(rejected.allowed());
        assertEquals(0, pool.size());
    }

    private static Transaction tx(OutPoint point, long value) {
        return new Transaction(2, List.of(new TxIn(point, new byte[0], TxIn.FINAL_SEQUENCE,
                new Witness(List.of(new byte[]{0x51})))), List.of(new TxOut(value, SCRIPT)), new UInt32(0));
    }
}
