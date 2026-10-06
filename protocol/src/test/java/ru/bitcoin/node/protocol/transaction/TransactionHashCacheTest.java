package ru.bitcoin.node.protocol.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.*;

class TransactionHashCacheTest {
    @Test
    void immutableHashesMatchSerializationAcrossThreadsAndDefensiveCopies() {
        byte[] script = {0x51};
        byte[] witness = {1, 2, 3};
        var input = new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(0)),
                script, TxIn.FINAL_SEQUENCE, new Witness(List.of(witness)));
        var tx = new Transaction(2, List.of(input), List.of(new TxOut(10, script)), new UInt32(0));
        var expectedId = Hash256Digest.hash(TransactionSerializer.serializeLegacy(tx));
        var expectedWitnessId = Hash256Digest.hash(TransactionSerializer.serialize(tx));
        var jobs = java.util.stream.IntStream.range(0, 32).mapToObj(i -> CompletableFuture.runAsync(() -> {
            assertEquals(expectedId, tx.txId());
            assertEquals(expectedWitnessId, tx.wtxId());
        })).toArray(CompletableFuture[]::new);
        CompletableFuture.allOf(jobs).join();
        script[0] = 0;
        witness[0] = 0;
        input.scriptSig()[0] = 0;
        input.witness().item(0)[0] = 0;
        tx.outputs().getFirst().scriptPubKey()[0] = 0;
        assertEquals(expectedId, Hash256Digest.hash(TransactionSerializer.serializeLegacy(tx)));
        assertEquals(expectedWitnessId, Hash256Digest.hash(TransactionSerializer.serialize(tx)));
        assertSame(tx.txId(), tx.txId());
        assertSame(tx.wtxId(), tx.wtxId());
        var legacy = new Transaction(1, List.of(new TxIn(input.previousOutput(), new byte[0], TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(1, new byte[]{0x51})), new UInt32(0));
        assertSame(legacy.txId(), legacy.wtxId());
    }
}
