package ru.bitcoin.node.rvn.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.hash.*;
import ru.bitcoin.node.crypto.secp256k1.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.*;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RvnNativeTransactionChecksTest {
    private static final int FLAGS = ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS | ScriptVerifyFlags.DERSIG;
    private final OutPoint source = new OutPoint(new Hash256(new byte[32]), new UInt32(0));

    private Transaction tx(byte[] sig, Witness witness, long sequence, long lock) {
        return new Transaction(2, List.of(new TxIn(source, sig, new UInt32(sequence), witness)),
                List.of(new TxOut(900, new byte[]{0x51})), new UInt32(lock));
    }
    private RvnCoin coin(byte[] script) { return new RvnCoin(new TxOut(1000, script), 1, false); }
    private void verify(Transaction tx, RvnCoin coin) { RvnNativeScriptChecks.requireValid(tx, 0, coin, FLAGS); }
    private static byte[] push(byte[] data) {
        byte[] out = new byte[data.length + 1]; out[0] = (byte) data.length;
        System.arraycopy(data, 0, out, 1, data.length); return out;
    }
    private static byte[] program(int version, byte[] hash) {
        byte[] script = new byte[hash.length + 2]; script[0] = (byte) version; script[1] = (byte) hash.length;
        System.arraycopy(hash, 0, script, 2, hash.length); return script;
    }
    private static byte[] p2sh(byte[] redeem) {
        byte[] script = new byte[23]; script[0] = (byte) 0xa9; script[1] = 20; script[22] = (byte) 0x87;
        System.arraycopy(Hash160.hash(redeem), 0, script, 2, 20); return script;
    }

    @Test
    void signedP2pkhAndP2wpkhVerifyAndRejectOutputOrAmountTampering() {
        var key = new PrivateKey(BigInteger.ONE);
        byte[] pubkey = Secp256k1.publicKey(key).compressed();
        byte[] hash = Hash160.hash(pubkey);
        byte[] scriptCode = P2pkhScript.scriptPubKey(hash);
        for (boolean witness : new boolean[]{false, true}) {
            var unsigned = tx(new byte[0], Witness.EMPTY, 0xffff_ffffL, 0);
            byte[] digest = witness ? WitnessV0SignatureHash.calculate(unsigned, 0, scriptCode, 1000, 1)
                    : LegacySignatureHash.calculate(unsigned, 0, scriptCode, 1);
            byte[] der = Secp256k1.sign(digest, key).toDer();
            byte[] signature = Arrays.copyOf(der, der.length + 1); signature[der.length] = 1;
            var signed = witness ? tx(new byte[0], new Witness(List.of(signature, pubkey)), 0xffff_ffffL, 0)
                    : tx(P2pkhScript.scriptSig(signature, pubkey), Witness.EMPTY, 0xffff_ffffL, 0);
            var coin = coin(witness ? program(0, hash) : scriptCode);
            assertDoesNotThrow(() -> verify(signed, coin));
            var changed = new Transaction(2, signed.inputs(), List.of(new TxOut(899, new byte[]{0x51})), signed.lockTime());
            assertThrows(SecurityException.class, () -> verify(changed, coin));
            if (witness) assertThrows(SecurityException.class, () -> verify(signed,
                    new RvnCoin(new TxOut(1001, coin.output().scriptPubKey()), 1, false)));
        }
    }

    @Test
    void nativeAndWrappedP2wshCheckWitnessHashAndCanonicalWrapper() {
        byte[] script = {0x51};
        byte[] redeem = program(0, Sha256.hash(script));
        var witness = new Witness(List.of(script));
        assertDoesNotThrow(() -> verify(tx(new byte[0], witness, 0xffff_ffffL, 0), coin(redeem)));
        assertDoesNotThrow(() -> verify(tx(push(redeem), witness, 0xffff_ffffL, 0), coin(p2sh(redeem))));
        assertThrows(SecurityException.class, () -> verify(tx(new byte[0], new Witness(List.of(new byte[]{0})),
                0xffff_ffffL, 0), coin(redeem)));
        byte[] noncanonical = new byte[redeem.length + 2]; noncanonical[0] = 0x4c; noncanonical[1] = (byte) redeem.length;
        System.arraycopy(redeem, 0, noncanonical, 2, redeem.length);
        assertThrows(SecurityException.class, () -> verify(tx(noncanonical, witness, 0xffff_ffffL, 0), coin(p2sh(redeem))));
        assertThrows(SecurityException.class, () -> verify(tx(new byte[]{0}, witness, 0xffff_ffffL, 0), coin(redeem)));
    }

    @Test
    void legacyP2shExecutesRedeemAndUnexpectedWitnessIsRejected() {
        var valid = tx(push(new byte[]{0x51}), Witness.EMPTY, 0xffff_ffffL, 0);
        assertDoesNotThrow(() -> verify(valid, coin(p2sh(new byte[]{0x51}))));
        assertThrows(SecurityException.class, () -> verify(tx(push(new byte[]{0}), Witness.EMPTY,
                0xffff_ffffL, 0), coin(p2sh(new byte[]{0}))));
        assertThrows(SecurityException.class, () -> verify(tx(new byte[0], new Witness(List.of(new byte[]{1})),
                0xffff_ffffL, 0), coin(new byte[]{0x51})));
        assertThrows(SecurityException.class, () -> verify(tx(new byte[0], Witness.EMPTY,
                0xffff_ffffL, 0), coin(new byte[]{0})));
    }

    @Test
    void upgradedWitnessIsNotTreatedAsBitcoinTaprootOrPolicy() {
        byte[] hash = new byte[32]; hash[0] = 1;
        var unsigned = tx(new byte[0], Witness.EMPTY, 0xffff_ffffL, 0);
        assertDoesNotThrow(() -> verify(unsigned, coin(program(0x51, hash))));
        assertThrows(IllegalArgumentException.class, () -> RvnNativeScriptChecks.requireValid(unsigned, 0,
                coin(program(0x51, hash)), FLAGS | ScriptVerifyFlags.TAPROOT));
        assertThrows(IllegalArgumentException.class, () -> RvnNativeScriptChecks.requireValid(unsigned, 0,
                coin(new byte[]{0x51}), FLAGS | ScriptVerifyFlags.LOW_S));
        assertThrows(IllegalArgumentException.class, () -> RvnNativeScriptChecks.requireValid(unsigned, 0,
                coin(new byte[]{0x51}), ScriptVerifyFlags.WITNESS));
        assertThrows(SecurityException.class, () -> verify(unsigned, coin(program(0, new byte[]{1, 2}))));
        assertThrows(SecurityException.class, () -> verify(unsigned, coin(program(0x51, new byte[32]))));
    }

    @Test
    void absoluteLocktimeUsesStrictHeightAndCallerSuppliedTimeCutoff() {
        var coin = coin(new byte[]{0x51});
        RvnCoinView view = point -> point.equals(source) ? Optional.of(coin) : Optional.empty();
        for (long lock : new long[]{200, 1_800_000_000}) {
            var pending = tx(new byte[0], Witness.EMPTY, 0xffff_fffeL, lock);
            long height = lock < 500_000_000 ? lock : 200;
            long cutoff = lock < 500_000_000 ? 1_800_000_000 : lock;
            assertThrows(SecurityException.class, () -> RvnNativeTransactionChecks.transactions(height, cutoff,
                    List.of(pending), view, FLAGS));
            assertEquals(100, RvnNativeTransactionChecks.transactions(height + 1, cutoff + 1,
                    List.of(pending), view, FLAGS).fees());
            assertEquals(100, RvnNativeTransactionChecks.transactions(height, cutoff,
                    List.of(tx(new byte[0], Witness.EMPTY, 0xffff_ffffL, lock)), view, FLAGS).fees());
        }
    }

    @Test
    void dependencyInputScriptsUseCreatedCoinsAndFailureDoesNotMutateParent() {
        var coins = new HashMap<OutPoint, RvnCoin>(); coins.put(source, coin(new byte[]{0x51}));
        RvnCoinView view = point -> Optional.ofNullable(coins.get(point));
        var first = tx(new byte[0], Witness.EMPTY, 0xffff_ffffL, 0);
        var point = new OutPoint(first.txId(), new UInt32(0));
        var second = new Transaction(2, List.of(new TxIn(point, new byte[0], TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(800, new byte[]{0x51})), new UInt32(0));
        assertEquals(200, RvnNativeTransactionChecks.transactions(200, 1_800_000_000,
                List.of(first, second), view, FLAGS).fees());
        coins.put(source, coin(new byte[]{0}));
        assertThrows(SecurityException.class, () -> RvnNativeTransactionChecks.transactions(200, 1_800_000_000,
                List.of(first, second), view, FLAGS));
        assertEquals(1, coins.size());
        assertEquals(0, coins.get(source).output().scriptPubKey()[0]);
    }

    @Test
    void templatePathActuallyExecutesScriptsBeforeReturningCandidate() {
        var payout = RvnPayoutAddress.parse(RvnPayoutAndCoinbaseTest.encoded(60, new byte[20]),
                ru.bitcoin.node.rvn.protocol.RvnNetwork.MAINNET);
        var coins = new HashMap<OutPoint, RvnCoin>(); coins.put(source, coin(new byte[]{0x51}));
        RvnCoinView view = point -> Optional.ofNullable(coins.get(point));
        var selected = List.of(tx(new byte[0], Witness.EMPTY, 0xffff_ffffL, 0));
        var block = RvnBlockTemplateBuilder.buildWithNativeChecks(0x20000000, new byte[32], 4_200_000,
                1_800_000_000, 0x1b012345, 0, payout, selected, view, true, 1_799_999_000, FLAGS);
        assertEquals(RvnCoinbase.subsidy(4_200_000) + 100, block.transactions().getFirst().outputs().getFirst().value());
        coins.put(source, coin(new byte[]{0}));
        assertThrows(SecurityException.class, () -> RvnBlockTemplateBuilder.buildWithNativeChecks(0x20000000,
                new byte[32], 4_200_000, 1_800_000_000, 0x1b012345, 0, payout, selected, view, true,
                1_799_999_000, FLAGS));
    }
}
