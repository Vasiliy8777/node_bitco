package ru.bitcoin.node.rvn.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.hash.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.ScriptVerifyFlags;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnSequenceAndSigopsTest {
    private static final int FLAGS = ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS;
    private final OutPoint source = new OutPoint(new Hash256(new byte[32]), new UInt32(0));
    private RvnCoin coin(long height) { return new RvnCoin(new TxOut(1000, new byte[]{0x51}), height, false); }
    private Transaction tx(int version, long sequence) {
        return new Transaction(version, List.of(new TxIn(source, new byte[0], new UInt32(sequence))),
                List.of(new TxOut(900, new byte[]{0x51})), new UInt32(0));
    }
    private RvnSpendContext context(long height, long mtp, boolean enabled) {
        return new RvnSpendContext(height, mtp, mtp, enabled, FLAGS, h -> {
            assertEquals(99, h); return 10_000;
        });
    }
    private void check(Transaction tx, RvnCoin coin, RvnSpendContext context) {
        RvnNativeTransactionChecks.transactions(context, List.of(tx),
                point -> point.equals(source) ? Optional.of(coin) : Optional.empty());
    }

    @Test
    void relativeHeightBoundaryDisableActivationAndUnsignedVersion() {
        assertThrows(SecurityException.class, () -> check(tx(2, 2), coin(100), context(101, 20_000, true)));
        assertDoesNotThrow(() -> check(tx(2, 2), coin(100), context(102, 20_000, true)));
        assertDoesNotThrow(() -> check(tx(2, 0x8000_0002L), coin(100), context(101, 20_000, true)));
        assertDoesNotThrow(() -> check(tx(1, 2), coin(100), context(101, 20_000, true)));
        assertDoesNotThrow(() -> check(tx(2, 2), coin(100), context(101, 20_000, false)));
        assertThrows(SecurityException.class, () -> check(tx(-1, 2), coin(100), context(101, 20_000, true)));
    }

    @Test
    void relativeTimeUsesCoinPreviousBlockMtpAnd512SecondUnits() {
        long sequence = (1L << 22) | 2;
        assertThrows(SecurityException.class, () -> check(tx(2, sequence), coin(100), context(200, 11_023, true)));
        assertDoesNotThrow(() -> check(tx(2, sequence), coin(100), context(200, 11_024, true)));
        var unavailable = new RvnSpendContext(200, 20_000, 20_000, true, FLAGS,
                h -> { throw new SecurityException("Missing ancestor"); });
        assertThrows(SecurityException.class, () -> check(tx(2, sequence), coin(100), unavailable));
    }

    @Test
    void inBlockDependenciesRequireZeroRelativeDelay() {
        var first = tx(2, 0xffff_ffffL);
        var point = new OutPoint(first.txId(), new UInt32(0));
        for (long sequence : new long[]{0, 1, 1L << 22, (1L << 22) | 1}) {
            var second = new Transaction(2, List.of(new TxIn(point, new byte[0], new UInt32(sequence))),
                    List.of(new TxOut(800, new byte[]{0x51})), new UInt32(0));
            var context = new RvnSpendContext(200, 20_000, 20_000, true, FLAGS,
                    h -> { throw new AssertionError("In-block coin must use parent MTP"); });
            RvnCoinView view = p -> p.equals(source) ? Optional.of(coin(100)) : Optional.empty();
            if ((sequence & 0xffff) == 0) assertEquals(200,
                    RvnNativeTransactionChecks.transactions(context, List.of(first, second), view).fees());
            else assertThrows(SecurityException.class, () ->
                    RvnNativeTransactionChecks.transactions(context, List.of(first, second), view));
        }
    }

    @Test
    void legacySigopLimitIncludesCoinbaseAndDoesNotDoubleForRip2() {
        var payout = RvnPayoutAddress.parse(RvnPayoutAndCoinbaseTest.encoded(60, new byte[20]), RvnNetwork.MAINNET);
        RvnCoinView empty = point -> Optional.empty();
        for (int count : new int[]{20_000, 20_001}) {
            byte[] sigops = new byte[count]; Arrays.fill(sigops, (byte) 0xac);
            var original = RvnCoinbase.create(4_200_000, 0, 0, payout);
            var coinbase = new Transaction(original.version(), original.inputs(),
                    List.of(new TxOut(0, sigops)), original.lockTime());
            byte[] raw = RvnBlockTemplateBuilder.build(0x20000000, new byte[32], 4_200_000,
                    1_800_000_000, 0x1b012345, 0, payout, List.of(), 0, true).header().serialize();
            System.arraycopy(coinbase.txId().bytes(), 0, raw, 36, 32);
            try {
                var block = new RvnBlock(ru.bitcoin.node.rvn.protocol.RvnBlockHeader.parse(raw,
                        ru.bitcoin.node.rvn.protocol.RvnActivation.MAINNET_KAWPOW_TIME), List.of(coinbase));
                var context = context(4_200_000, 1_799_999_000, true);
                if (count == 20_000) assertDoesNotThrow(() -> RvnNativeTransactionChecks.block(block, context, true, empty));
                else assertThrows(SecurityException.class, () -> RvnNativeTransactionChecks.block(block, context, true, empty));
            } catch (java.io.IOException e) { throw new AssertionError(e); }
        }
    }

    @Test
    void witnessAndP2shSigopsUseCorrectWeightsAndIgnorePushedBytes() {
        byte[] redeem = {0x52, (byte) 0xae}; // Accurate 2-of-N multisig: two sigops.
        byte[] sig = {2, 0x52, (byte) 0xae};
        byte[] p2sh = new byte[23]; p2sh[0] = (byte) 0xa9; p2sh[1] = 20; p2sh[22] = (byte) 0x87;
        System.arraycopy(Hash160.hash(redeem), 0, p2sh, 2, 20);
        var base = tx(2, 0xffff_ffffL);
        var tx = new Transaction(2, List.of(new TxIn(source, sig, TxIn.FINAL_SEQUENCE)), base.outputs(), base.lockTime());
        assertEquals(0, RvnSigOpCost.legacy(tx));
        assertEquals(8, RvnSigOpCost.input(tx, 0, new RvnCoin(new TxOut(1000, p2sh), 1, false), FLAGS));
        byte[] program = new byte[34]; program[1] = 32;
        System.arraycopy(Sha256.hash(redeem), 0, program, 2, 32);
        var witnessed = new Transaction(2, List.of(new TxIn(source, new byte[0], TxIn.FINAL_SEQUENCE,
                new Witness(List.of(redeem)))), base.outputs(), base.lockTime());
        assertEquals(2, RvnSigOpCost.input(witnessed, 0, new RvnCoin(new TxOut(1000, program), 1, false), FLAGS));
        byte[] keyProgram = new byte[22]; keyProgram[1] = 20;
        assertEquals(1, RvnSigOpCost.input(witnessed, 0, new RvnCoin(new TxOut(1000, keyProgram), 1, false), FLAGS));
    }
}
