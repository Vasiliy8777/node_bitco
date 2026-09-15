package ru.bitcoin.node.consensus.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.crypto.secp256k1.*;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.LegacySignatureHash;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SignetBlockValidatorTest {
    @Test void permitsGenesisAndOpTrueChallenge() {
        assertDoesNotThrow(() -> SignetBlockValidator.validate(GenesisBlockFactory.create(NetworkParametersRegistry.signet()), NetworkParametersRegistry.signet()));
        assertDoesNotThrow(() -> SignetBlockValidator.validateChallenge(block(null, 100), new byte[]{0x51}));
        assertThrows(BlockValidationException.class, () -> SignetBlockValidator.validate(block(null, 100), NetworkParametersRegistry.signet()));
    }
    @Test void checksSignedBlockDataAndRejectsTimestampMutation() {
        var key = new PrivateKey(BigInteger.valueOf(3));
        byte[] publicKey = Secp256k1.publicKey(key).compressed();
        var challengeBytes = new ByteArrayOutputStream(); challengeBytes.write(33); challengeBytes.writeBytes(publicKey); challengeBytes.write(0xac);
        byte[] challenge = challengeBytes.toByteArray();
        var placeholder = block(new byte[]{0, 0}, 100);
        var virtual = SignetBlockValidator.createTransactions(placeholder, challenge);
        byte[] hash = LegacySignatureHash.calculate(virtual.toSign(), 0, challenge, 1);
        byte[] der = Secp256k1.sign(hash, key).toDer();
        byte[] signature = Arrays.copyOf(der, der.length + 1); signature[signature.length - 1] = 1;
        var scriptSig = new ByteArrayOutputStream(); scriptSig.write(signature.length); scriptSig.writeBytes(signature);
        var solution = new ByteArrayOutputStream(); solution.writeBytes(CompactSize.encode(scriptSig.size()));
        solution.writeBytes(scriptSig.toByteArray()); solution.write(0);
        var signed = block(solution.toByteArray(), 100);
        assertEquals(virtual.toSpend().txId(), SignetBlockValidator.createTransactions(signed, challenge).toSpend().txId());
        assertDoesNotThrow(() -> SignetBlockValidator.validateChallenge(signed, challenge));
        assertThrows(BlockValidationException.class, () -> SignetBlockValidator.validateChallenge(block(solution.toByteArray(), 101), challenge));
    }
    @Test void rejectsMalformedAndTrailingSolutionBytes() {
        for (byte[] solution : List.of(new byte[]{0}, new byte[]{0, 0, 1}, new byte[]{(byte) 0xfe, -1, -1, -1, 127})) {
            assertThrows(BlockValidationException.class, () -> SignetBlockValidator.validateChallenge(block(solution, 100), new byte[]{0x51}));
        }
    }
    private static Block block(byte[] solution, long time) {
        var script = new ByteArrayOutputStream();
        script.writeBytes(HexUtils.decode("6a24aa21a9ed")); script.writeBytes(Hash256Digest.hash(new byte[64]).bytes());
        if (solution != null) {
            int length = 4 + solution.length;
            if (length >= 76) { script.write(76); script.write(length); } else script.write(length);
            script.writeBytes(HexUtils.decode("ecc7daa2")); script.writeBytes(solution);
        }
        var tx = new Transaction(1, List.of(new TxIn(OutPoint.coinbase(), new byte[]{0x51, 0}, TxIn.FINAL_SEQUENCE,
                new Witness(List.of(new byte[32])))), List.of(new TxOut(0, script.toByteArray())), new UInt32(0));
        return new Block(new BlockHeader(4, new Hash256(new byte[32]), MerkleTree.calculateRoot(List.of(tx.txId())),
                new UInt32(time), new UInt32(0x1e0377ae), new UInt32(0)), List.of(tx));
    }
}
