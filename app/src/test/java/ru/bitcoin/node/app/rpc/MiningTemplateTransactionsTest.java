package ru.bitcoin.node.app.rpc;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.money.BlockSubsidy;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.mempool.MempoolEntry;
import ru.bitcoin.node.mining.coinbase.CoinbaseBuilder;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class MiningTemplateTransactionsTest {
    @Test
    void rendersDeterministicParentDependenciesAndChecksCoinbaseAccounting() {
        var parameters = NetworkParametersRegistry.regtest();
        Transaction parent = transaction(new Hash256(new byte[32]), 0, 4_000_000_000L);
        Transaction child = transaction(parent.txId(), 0, 3_999_999_000L);
        long parentFee = 1_000L;
        long childFee = 500L;
        var entries = List.of(entry(parent, parentFee), entry(child, childFee));
        long fees = parentFee + childFee;
        var coinbase = CoinbaseBuilder.build(1, parameters, fees, new byte[]{0x51}, new byte[8], List.of(parent, child));
        Block block = block(List.of(coinbase, parent, child));

        var rendered = MiningTemplateTransactions.build(block, entries, 1, parameters);

        assertEquals(List.of(), rendered.get(0).get("depends"));
        assertEquals(List.of(1), rendered.get(1).get("depends"));
        assertEquals(parentFee, rendered.get(0).get("fee"));
        assertEquals(TransactionWeight.calculate(child), rendered.get(1).get("weight"));
    }

    @Test
    void rejectsForwardDependencyInsteadOfSilentlyDroppingIt() {
        var parameters = NetworkParametersRegistry.regtest();
        Transaction parent = transaction(new Hash256(new byte[32]), 0, 4_000_000_000L);
        Transaction child = transaction(parent.txId(), 0, 3_999_999_000L);
        var entries = List.of(entry(parent, 1_000L), entry(child, 500L));
        long fees = 1_500L;
        var coinbase = CoinbaseBuilder.build(1, parameters, fees, new byte[]{0x51}, new byte[8], List.of(parent, child));
        Block reversed = block(List.of(coinbase, child, parent));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> MiningTemplateTransactions.build(reversed, entries, 1, parameters));
        assertTrue(error.getMessage().contains("parent-before-child"));
    }

    @Test
    void rejectsMissingSnapshotEntryAndStaleWeightMetadata() {
        var parameters = NetworkParametersRegistry.regtest();
        Transaction tx = transaction(new Hash256(new byte[32]), 0, 4_000_000_000L);
        var coinbase = CoinbaseBuilder.build(1, parameters, 1_000L, new byte[]{0x51}, new byte[8], List.of(tx));
        Block block = block(List.of(coinbase, tx));

        assertThrows(IllegalStateException.class,
                () -> MiningTemplateTransactions.build(block, List.of(), 1, parameters));
        var stale = new MempoolEntry(tx, 1_000L, TransactionWeight.calculate(tx) + 1, 0, 0);
        assertThrows(IllegalStateException.class,
                () -> MiningTemplateTransactions.build(block, List.of(stale), 1, parameters));
    }

    private static MempoolEntry entry(Transaction tx, long fee) {
        return new MempoolEntry(tx, fee, TransactionWeight.calculate(tx), 0, 0);
    }

    private static Transaction transaction(Hash256 previous, long vout, long value) {
        return new Transaction(2, List.of(new TxIn(new OutPoint(previous, new UInt32(vout)),
                new byte[]{0x51}, TxIn.FINAL_SEQUENCE)), List.of(new TxOut(value, new byte[]{0x51})), new UInt32(0));
    }

    private static Block block(List<Transaction> transactions) {
        Hash256 merkle = MerkleTree.calculateRoot(transactions.stream().map(Transaction::txId).toList());
        return new Block(new BlockHeader(0x20000000, new Hash256(new byte[32]), merkle,
                new UInt32(1_800_000_000L), new UInt32(0x207fffffL), new UInt32(0)), transactions);
    }
}
