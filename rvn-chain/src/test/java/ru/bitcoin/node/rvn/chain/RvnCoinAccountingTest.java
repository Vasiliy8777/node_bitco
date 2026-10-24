package ru.bitcoin.node.rvn.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnCoinAccountingTest {
    private static final long HEIGHT = 4_200_000;
    private final OutPoint source = point(7);
    private final Map<OutPoint, RvnCoin> coins = new HashMap<>();
    private final RvnCoinView view = point -> Optional.ofNullable(coins.get(point));
    private final RvnPayoutAddress payout = RvnPayoutAddress.parse(
            RvnPayoutAndCoinbaseTest.encoded(60, new byte[20]), RvnNetwork.MAINNET);

    private static OutPoint point(int marker) {
        byte[] hash = new byte[32]; hash[0] = (byte) marker;
        return new OutPoint(new Hash256(hash), new UInt32(0));
    }
    private static OutPoint output(Transaction tx) { return new OutPoint(tx.txId(), new UInt32(0)); }
    private static Transaction spend(OutPoint source, long value) {
        return new Transaction(1, List.of(new TxIn(source, new byte[0], TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(value, new byte[]{0x51})), new UInt32(0));
    }
    private RvnBlock template(List<Transaction> transactions, long fees) {
        return RvnBlockTemplateBuilder.build(0x20000000, new byte[32], HEIGHT, 1_800_000_000,
                0x1b012345, 0, payout, transactions, fees, true);
    }
    private void fund(long value, long height, boolean coinbase) {
        coins.put(source, new RvnCoin(new TxOut(value, new byte[]{0x51}), height, coinbase));
    }

    @Test void historicalAssetValuesAreExcludedFromFeesButRetainedInCoins() {
        fund(1000, HEIGHT - 1, false);
        byte[] script = ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.encodeCanonical(payout.scriptPubKey(),
                new ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.Reissue("TOKEN", 1, -1, true, null));
        script[26] = (byte) 0xff; // Core's historical classifier ignores the envelope push length.
        var tx = new Transaction(1, List.of(new TxIn(source, new byte[0], TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(900, new byte[]{0x51}), new TxOut(5000, script)), new UInt32(0));
        var legacy = RvnCoinAccounting.transactions(HEIGHT, List.of(tx), view, false);
        assertEquals(100, legacy.fees());
        assertEquals(5000, legacy.created().get(new OutPoint(tx.txId(), new UInt32(1))).output().value());
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.transactions(HEIGHT, List.of(tx), view, true));
        assertEquals(100, RvnCoinAccounting.block(template(List.of(tx), 100), HEIGHT, true, view, false).fees());
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.block(template(List.of(tx), 101), HEIGHT, true, view, false));
        var negative = new Transaction(1, tx.inputs(), List.of(new TxOut(-1, script)), new UInt32(0));
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.transactions(HEIGHT, List.of(negative), view, false));
    }

    @Test
    void orderedDependenciesProduceNetChangesAndUndoWithoutMutatingParent() {
        fund(1000, HEIGHT - 200, true);
        var original = Map.copyOf(coins);
        var first = spend(source, 900);
        var second = spend(output(first), 850);
        var changes = RvnCoinAccounting.transactions(HEIGHT, List.of(first, second), view);
        assertEquals(150, changes.fees());
        assertEquals(original, changes.spent());
        assertEquals(Set.of(output(second)), changes.created().keySet());
        assertEquals(new RvnCoin(second.outputs().getFirst(), HEIGHT, false), changes.created().get(output(second)));
        assertEquals(original, coins);
        assertThrows(UnsupportedOperationException.class, () -> changes.created().clear());
        // Applying and undoing the net delta restores the exact parent coins.
        var connected = new HashMap<>(coins);
        changes.spent().keySet().forEach(connected::remove);
        connected.putAll(changes.created());
        changes.created().keySet().forEach(connected::remove);
        connected.putAll(changes.spent());
        assertEquals(original, connected);
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.transactions(HEIGHT, List.of(second, first), view));
    }

    @Test
    void rejectsCrossTransactionDoubleSpendMissingInputsAndOverspendAtomically() {
        fund(1000, HEIGHT - 1, false);
        var snapshot = Map.copyOf(coins);
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.transactions(HEIGHT,
                List.of(spend(source, 900), spend(source, 800)), view));
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.transactions(HEIGHT,
                List.of(spend(point(8), 1)), view));
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.transactions(HEIGHT,
                List.of(spend(source, 1001)), view));
        assertEquals(snapshot, coins);
    }

    @Test
    void coinbaseMaturityBoundaryAndFutureCoin() {
        fund(1000, HEIGHT - 99, true);
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.transactions(HEIGHT, List.of(spend(source, 900)), view));
        fund(1000, HEIGHT - 100, true);
        assertEquals(100, RvnCoinAccounting.transactions(HEIGHT, List.of(spend(source, 900)), view).fees());
        fund(1000, HEIGHT + 1, false);
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.transactions(HEIGHT, List.of(spend(source, 900)), view));
    }

    @Test
    void usesRvnMoneyRangeAndRejectsAggregateInputOverflow() {
        long aboveBitcoinLimit = 22_000_000L * RvnBlockBodyChecks.COIN;
        fund(aboveBitcoinLimit, HEIGHT - 1, false);
        assertEquals(1, RvnCoinAccounting.transactions(HEIGHT, List.of(spend(source, aboveBitcoinLimit - 1)), view).fees());
        fund(RvnBlockBodyChecks.MAX_MONEY, HEIGHT - 1, false);
        coins.put(point(8), new RvnCoin(new TxOut(1, new byte[]{0x51}), HEIGHT - 1, false));
        var tx = new Transaction(1, List.of(new TxIn(source, new byte[0], TxIn.FINAL_SEQUENCE),
                new TxIn(point(8), new byte[0], TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(1, new byte[]{0x51})), new UInt32(0));
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.transactions(HEIGHT, List.of(tx), view));
    }

    @Test
    void templateDerivesFeesAndBlockRejectsInflatedRewardAndHeight() {
        fund(1000, HEIGHT - 1, false);
        var tx = spend(source, 700);
        var block = RvnBlockTemplateBuilder.buildFromCoins(0x20000000, new byte[32], HEIGHT, 1_800_000_000,
                0x1b012345, 0, payout, List.of(tx), view, true);
        assertEquals(RvnCoinbase.subsidy(HEIGHT) + 300, block.transactions().getFirst().outputs().getFirst().value());
        var changes = RvnCoinAccounting.block(block, HEIGHT, true, view);
        assertEquals(300, changes.fees());
        assertTrue(changes.created().get(output(block.transactions().getFirst())).coinbase());
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.block(template(List.of(tx), 301), HEIGHT, true, view));
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.block(block, HEIGHT + 1, true, view));
        // Claiming less than the available reward is valid.
        assertEquals(300, RvnCoinAccounting.block(template(List.of(tx), 299), HEIGHT, true, view).fees());
    }

    @Test
    void rejectsUnspentOutputOverwriteAndSameBlockCoinbaseSpend() {
        fund(1000, HEIGHT - 1, false);
        var tx = spend(source, 900);
        coins.put(output(tx), new RvnCoin(tx.outputs().getFirst(), HEIGHT - 10, false));
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.transactions(HEIGHT, List.of(tx), view));
        coins.remove(output(tx));
        var coinbase = RvnCoinbase.create(HEIGHT, 0, 0, payout);
        var block = template(List.of(spend(output(coinbase), 1)), 0);
        assertEquals(coinbase.txId(), block.transactions().getFirst().txId());
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.block(block, HEIGHT, true, view));
    }

    @Test
    void prunesUnspendableOutputsAndRejectsDuplicateInput() {
        fund(1000, HEIGHT - 1, false);
        var tx = new Transaction(1, List.of(new TxIn(source, new byte[0], TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(500, new byte[]{0x6a}), new TxOut(400, new byte[10_001])), new UInt32(0));
        var changes = RvnCoinAccounting.transactions(HEIGHT, List.of(tx), view);
        assertEquals(100, changes.fees());
        assertTrue(changes.created().isEmpty());
        var duplicate = new Transaction(1, List.of(tx.inputs().getFirst(), tx.inputs().getFirst()),
                List.of(new TxOut(1, new byte[]{0x51})), new UInt32(0));
        assertThrows(SecurityException.class, () -> RvnCoinAccounting.transactions(HEIGHT, List.of(duplicate), view));
    }
}
