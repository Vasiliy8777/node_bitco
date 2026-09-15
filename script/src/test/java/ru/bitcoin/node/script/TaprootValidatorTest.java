package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.SchnorrSignature;
import ru.bitcoin.node.crypto.hash.TaggedHash;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.validation.TaprootValidator;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaprootValidatorTest {
    private static final byte[] INTERNAL = HexUtils.decode("f9308a019258c31049344f85f89d5229b531c845836f99b08601f113bce036f9");
    private static final int FLAGS = ScriptVerifyFlags.TAPROOT | ScriptVerifyFlags.CHECKLOCKTIMEVERIFY | ScriptVerifyFlags.CHECKSEQUENCEVERIFY;

    @Test void validatesControlBlockAndRejectsParityMismatch() {
        var spend = spend("51", 0xc0, List.of());
        assertDoesNotThrow(() -> spend.validate(FLAGS));
        var witness = new ArrayList<byte[]>(spend.witness());
        byte[] control = witness.getLast().clone(); control[0] ^= 1;
        witness.set(witness.size() - 1, control);
        assertThrows(ScriptExecutionException.class, () -> spend.withWitness(witness).validate(FLAGS));
    }
    @Test void rejectsFalseAndUncleanFinalStack() {
        for (String script : List.of("00", "5151")) {
            assertThrows(ScriptExecutionException.class, () -> spend(script, 0xc0, List.of()).validate(FLAGS));
        }
    }
    @Test void opSuccessPrecedesBadTrailingBytesAndInitialStackLimits() {
        var spend = spend("504c", 0xc0, List.of(new byte[521]));
        assertDoesNotThrow(() -> spend.validate(FLAGS));
        assertThrows(ScriptExecutionException.class, () -> spend.validate(FLAGS | ScriptVerifyFlags.DISCOURAGE_OP_SUCCESS));
        assertThrows(ScriptExecutionException.class, () -> spend("4cff50", 0xc0, List.of()).validate(FLAGS));
    }
    @Test void successByteInsidePushIsNotAnOpcode() {
        assertThrows(ScriptExecutionException.class, () -> spend("01507500", 0xc0, List.of()).validate(FLAGS));
    }
    @Test void unknownLeafVersionHasSeparatePolicy() {
        var spend = spend("00", 0xc2, List.of(new byte[521]));
        assertDoesNotThrow(() -> spend.validate(FLAGS));
        assertThrows(ScriptExecutionException.class, () -> spend.validate(FLAGS | ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_TAPROOT_VERSION));
    }
    @Test void enforcesInitialStackLimitsWithoutOpSuccess() {
        assertThrows(ScriptExecutionException.class, () -> spend("7551", 0xc0, List.of(new byte[521])).validate(FLAGS));
        assertThrows(ScriptExecutionException.class, () -> spend("51", 0xc0, Collections.nCopies(1001, new byte[0])).validate(FLAGS));
    }
    @Test void removesLegacyScriptSizeAndOpcodeCountLimits() {
        assertDoesNotThrow(() -> spend("61".repeat(10_001) + "51", 0xc0, List.of()).validate(FLAGS));
    }
    @Test void minimalIfIsConsensusAndMultisigOnlyFailsWhenExecuted() {
        assertThrows(ScriptExecutionException.class, () -> spend("635168", 0xc0, List.of(new byte[]{2})).validate(FLAGS));
        assertDoesNotThrow(() -> spend("0063ae6851", 0xc0, List.of()).validate(FLAGS));
        assertThrows(ScriptExecutionException.class, () -> spend("ae", 0xc0, List.of()).validate(FLAGS));
    }
    @Test void unknownKeysAndSignatureBudgetAreEnforced() {
        assertDoesNotThrow(() -> spend("0101ac", 0xc0, List.of(new byte[]{1})).validate(FLAGS));
        assertThrows(ScriptExecutionException.class, () -> spend("0101ac", 0xc0, List.of(new byte[]{1}))
                .validate(FLAGS | ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_PUBKEYTYPE));
        assertThrows(ScriptExecutionException.class, () -> spend("760101ad".repeat(5) + "7551", 0xc0, List.of(new byte[]{1})).validate(FLAGS));
        assertThrows(ScriptExecutionException.class, () -> spend("00ac", 0xc0, List.of(new byte[0])).validate(FLAGS));
    }
    @Test void checkSigAddAcceptsEmptySignatureAndEnforcesNumberSize() {
        assertDoesNotThrow(() -> spend("000101ba519c", 0xc0, List.of(new byte[]{1})).validate(FLAGS));
        assertDoesNotThrow(() -> spend("510101ba519c", 0xc0, List.of(new byte[0])).validate(FLAGS));
        assertThrows(ScriptExecutionException.class, () -> spend("0500000000010101ba", 0xc0, List.of(new byte[0])).validate(FLAGS));
    }
    @Test void verifiesSignedScriptPathWithAnnexAndCodeSeparator() {
        // First OP_CODESEPARATOR is skipped; the executed separator has opcode index 4.
        String script = "0063ab68ab20" + HexUtils.encode(INTERNAL) + "ac";
        var unsigned = spend(script, 0xc0, List.of(new byte[0]));
        byte[] annex = HexUtils.decode("50010203");
        byte[] leaf = TaggedHash.hash("TapLeaf", new byte[]{(byte) 0xc0}, CompactSize.encode(HexUtils.decode(script).length), HexUtils.decode(script));
        byte[] hash = TaprootSignatureHash.calculate(unsigned.transaction(), 0, unsigned.coins(), 0, annex, leaf, 4);
        byte[] signature = sign(hash);
        var witness = new ArrayList<>(unsigned.witness());
        witness.set(0, signature); witness.add(annex);
        assertDoesNotThrow(() -> unsigned.withWitness(witness).validate(FLAGS));
        byte[] wrong = annex.clone(); wrong[1] ^= 1; witness.set(witness.size() - 1, wrong);
        assertThrows(ScriptExecutionException.class, () -> unsigned.withWitness(witness).validate(FLAGS));
    }

    // Deterministic test-only signer, independently checked by official verification vectors.
    private static byte[] sign(byte[] message) {
        BigInteger k = BigInteger.valueOf(7);
        var rPoint = Secp256k1.DOMAIN.getG().multiply(k).normalize();
        if (rPoint.getAffineYCoord().toBigInteger().testBit(0)) k = Secp256k1.N.subtract(k);
        byte[] r = rPoint.getAffineXCoord().getEncoded();
        BigInteger e = new BigInteger(1, TaggedHash.hash("BIP0340/challenge", r, INTERNAL, message)).mod(Secp256k1.N);
        byte[] s = org.bouncycastle.util.BigIntegers.asUnsignedByteArray(32, k.add(e.multiply(BigInteger.valueOf(3))).mod(Secp256k1.N));
        byte[] result = Arrays.copyOf(r, 64); System.arraycopy(s, 0, result, 32, 32);
        return result;
    }
    private static Spend spend(String hex, int version, List<byte[]> initial) {
        byte[] script = HexUtils.decode(hex);
        byte[] leaf = TaggedHash.hash("TapLeaf", new byte[]{(byte) version}, CompactSize.encode(script.length), script);
        BigInteger tweak = new BigInteger(1, TaggedHash.hash("TapTweak", INTERNAL, leaf));
        var point = SchnorrSignature.liftX(INTERNAL).add(Secp256k1.DOMAIN.getG().multiply(tweak)).normalize();
        byte[] key = point.getAffineXCoord().getEncoded();
        byte[] control = new byte[33]; control[0] = (byte) (version | (point.getAffineYCoord().toBigInteger().testBit(0) ? 1 : 0));
        System.arraycopy(INTERNAL, 0, control, 1, 32);
        var witness = new ArrayList<>(initial); witness.add(script); witness.add(control);
        byte[] output = new byte[34]; output[0] = 0x51; output[1] = 32; System.arraycopy(key, 0, output, 2, 32);
        return new Spend(key, List.of(new TxOut(1000, output)), witness);
    }
    private record Spend(byte[] key, List<TxOut> coins, List<byte[]> witness) {
        Transaction transaction() {
            return new Transaction(2, List.of(new TxIn(new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0)),
                    new byte[0], TxIn.FINAL_SEQUENCE, new Witness(witness))), List.of(new TxOut(900, new byte[]{0x51})), new UInt32(0));
        }
        Spend withWitness(List<byte[]> items) { return new Spend(key, coins, List.copyOf(items)); }
        void validate(int flags) { TaprootValidator.validate(transaction(), 0, coins, key, flags); }
    }
}
