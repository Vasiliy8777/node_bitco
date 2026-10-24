package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.hash.*;
import ru.bitcoin.node.crypto.secp256k1.*;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.*;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class RavencoinScriptDialectTest {
    private static final ScriptDialect RVN = ScriptDialect.RAVENCOIN;
    private static final int FLAGS = ScriptVerifyFlags.P2SH | ScriptVerifyFlags.DERSIG;
    private static Transaction tx(byte[] sig) {
        return new Transaction(2, List.of(new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(0)), sig, TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(1000, new byte[]{0x51})), new UInt32(0));
    }
    private static byte[] concat(byte[]... items) {
        var out = new ByteArrayOutputStream(); for (byte[] item : items) out.writeBytes(item); return out.toByteArray();
    }
    private static byte[] push(byte[] data) { return concat(new byte[]{(byte)data.length}, data); }

    @Test void opaqueSuffixIsNoopAndDefaultBitcoinStillRejectsAssetOpcode() {
        byte[] script = {0x51,(byte)0xc0,0x4e,(byte)0xff,(byte)0xab,0x65};
        assertTrue(LegacyScriptVerifier.verify(tx(new byte[0]), 0, new byte[0], script, FLAGS, RVN));
        assertFalse(LegacyScriptVerifier.verify(tx(new byte[0]), 0, new byte[0], script, FLAGS));
        var parsed = ScriptParser.parse(script, RVN);
        assertEquals(2, parsed.size()); assertFalse(parsed.getLast().isPushData()); assertEquals(4, parsed.getLast().dataLength());
        byte[] literal = {1,(byte)0xc0,0x75,0x51};
        assertTrue(LegacyScriptVerifier.verify(tx(new byte[0]), 0, new byte[0], literal, FLAGS, RVN));
        assertTrue(LegacyScriptVerifier.verify(tx(new byte[0]), 0, new byte[0], literal, FLAGS));
    }

    @Test void suffixElementLimitAndConditionalBalanceMatchCoreGetOp() {
        for (int length : new int[]{520,521}) {
            byte[] script = new byte[2+length]; script[0] = 0x51; script[1] = (byte)0xc0;
            assertEquals(length == 520, LegacyScriptVerifier.verify(tx(new byte[0]), 0, new byte[0], script, FLAGS, RVN));
        }
        byte[] skipped = {0,0x63,(byte)0xc0,0x68,0x51};
        assertFalse(LegacyScriptVerifier.verify(tx(new byte[0]), 0, new byte[0], skipped, FLAGS, RVN));
        assertTrue(LegacyScriptVerifier.verify(tx(new byte[0]), 0, new byte[0], skipped, FLAGS));
        byte[] sig = {0x51,(byte)0xc0,0x65};
        assertTrue(LegacyScriptVerifier.verify(tx(sig), 0, sig, new byte[]{0x51}, FLAGS, RVN));
        assertFalse(LegacyScriptVerifier.verify(tx(sig), 0, sig, new byte[]{0x51}, FLAGS));
    }

    @Test void signatureDeletionNeverExaminesOpaqueRemainder() {
        byte[] script = {1,1,(byte)0xc0,1,1};
        assertArrayEquals(new byte[]{(byte)0xc0,1,1}, LegacyScriptCode.findAndDeleteSignature(script, new byte[]{1}, RVN));
        assertArrayEquals(new byte[]{(byte)0xc0}, LegacyScriptCode.findAndDeleteSignature(script, new byte[]{1}));
    }

    @Test void sighashKeepsTailSeparatorsAndMalformedPushBytesWithIndependentSerialization() {
        byte[] code = {(byte)0xab,0x51,(byte)0xc0,(byte)0xab,0x4e,(byte)0xff};
        byte[] normalized = {0x51,(byte)0xc0,(byte)0xab,0x4e,(byte)0xff};
        byte[] serialized = TransactionSerializer.serializeLegacy(new Transaction(2,
                List.of(new TxIn(tx(new byte[0]).inputs().getFirst().previousOutput(), normalized, TxIn.FINAL_SEQUENCE)),
                tx(new byte[0]).outputs(), new UInt32(0)));
        byte[] expected = Hash256Digest.hashBytes(concat(serialized, new byte[]{1,0,0,0}));
        assertArrayEquals(expected, LegacySignatureHash.calculate(tx(new byte[0]), 0, code, 1, RVN));
        assertThrows(RuntimeException.class, () -> LegacySignatureHash.calculate(tx(new byte[0]), 0, code, 1));
    }

    @Test void realSignaturesCommitWholeSuffixAcrossAllLegacyHashModesAndExecutedSeparator() {
        var key = new PrivateKey(BigInteger.ONE); byte[] pub = Secp256k1.publicKey(key).compressed();
        byte[] prefix = P2pkhScript.scriptPubKey(Hash160.hash(pub));
        byte[] full = concat(new byte[]{(byte)0xab}, prefix, new byte[]{(byte)0xc0,(byte)0xab,0x4e,(byte)0xff});
        byte[] scriptCode = Arrays.copyOfRange(full, 1, full.length);
        for (int hashType : new int[]{1,2,3,0x81,0x82,0x83}) {
            byte[] digest = LegacySignatureHash.calculate(tx(new byte[0]), 0, scriptCode, hashType, RVN);
            byte[] signature = concat(Secp256k1.sign(digest, key).toDer(), new byte[]{(byte)hashType});
            byte[] sig = P2pkhScript.scriptSig(signature, pub);
            assertTrue(LegacyScriptVerifier.verify(tx(sig), 0, sig, full, FLAGS, RVN), "hashType="+hashType);
            byte[] changed = full.clone(); changed[changed.length-1] ^= 1;
            assertFalse(LegacyScriptVerifier.verify(tx(sig), 0, sig, changed, FLAGS, RVN));
        }
    }

    @Test void dialectPropagatesThroughP2shAndWitnessV0Scripts() {
        byte[] redeem = {0x51,(byte)0xc0,0x65};
        byte[] p2sh = concat(new byte[]{(byte)0xa9,20}, Hash160.hash(redeem), new byte[]{(byte)0x87});
        byte[] sig = push(redeem);
        assertTrue(LegacyScriptVerifier.verify(tx(sig), 0, sig, p2sh, FLAGS, RVN));
        assertFalse(LegacyScriptVerifier.verify(tx(sig), 0, sig, p2sh, FLAGS));
        var program = WitnessProgram.parse(concat(new byte[]{0,32}, Sha256.hash(redeem))).orElseThrow();
        var witness = new Witness(List.of(redeem));
        assertTrue(WitnessV0ScriptVerifier.verifyP2wsh(tx(new byte[0]), 0, new byte[0], witness, program, 1000, FLAGS | ScriptVerifyFlags.WITNESS, RVN));
        assertFalse(WitnessV0ScriptVerifier.verifyP2wsh(tx(new byte[0]), 0, new byte[0], witness, program, 1000, FLAGS | ScriptVerifyFlags.WITNESS));
    }
}
