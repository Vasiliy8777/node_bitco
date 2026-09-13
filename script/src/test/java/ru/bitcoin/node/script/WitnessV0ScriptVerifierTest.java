package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.crypto.secp256k1.EcdsaSignature;
import ru.bitcoin.node.crypto.secp256k1.PrivateKey;
import ru.bitcoin.node.crypto.secp256k1.PublicKey;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.protocol.transaction.Witness;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class WitnessV0ScriptVerifierTest {

    private static final long UTXO_AMOUNT =
            100_000L;

    private static final long OUTPUT_AMOUNT =
            90_000L;

    @Test
    void validP2wpkhSpendMustPass() {

        Fixture fixture =
                createFixture();

        assertTrue(
                WitnessV0ScriptVerifier.verifyP2wpkh(
                        fixture.transaction(),
                        0,
                        new byte[0],
                        fixture.witness(),
                        fixture.witnessProgram(),
                        UTXO_AMOUNT,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void wrongAmountMustFail() {

        Fixture fixture =
                createFixture();

        /*
         * Amount является частью BIP143 preimage.
         * Даже изменение на 1 sat меняет digest.
         */
        assertFalse(
                WitnessV0ScriptVerifier.verifyP2wpkh(
                        fixture.transaction(),
                        0,
                        new byte[0],
                        fixture.witness(),
                        fixture.witnessProgram(),
                        UTXO_AMOUNT + 1,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void nonEmptyNativeScriptSigMustFail() {

        Fixture fixture =
                createFixture();

        assertFalse(
                WitnessV0ScriptVerifier.verifyP2wpkh(
                        fixture.transaction(),
                        0,
                        new byte[]{0x00},
                        fixture.witness(),
                        fixture.witnessProgram(),
                        UTXO_AMOUNT,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void witnessWithOneItemMustFail() {

        Fixture fixture =
                createFixture();

        Witness witness =
                new Witness(
                        List.of(
                                fixture.witness()
                                        .item(0)
                        )
                );

        assertFalse(
                WitnessV0ScriptVerifier.verifyP2wpkh(
                        fixture.transaction(),
                        0,
                        new byte[0],
                        witness,
                        fixture.witnessProgram(),
                        UTXO_AMOUNT,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void witnessWithThreeItemsMustFail() {

        Fixture fixture =
                createFixture();

        Witness witness =
                new Witness(
                        List.of(
                                fixture.witness().item(0),
                                fixture.witness().item(1),
                                new byte[]{0x01}
                        )
                );

        assertFalse(
                WitnessV0ScriptVerifier.verifyP2wpkh(
                        fixture.transaction(),
                        0,
                        new byte[0],
                        witness,
                        fixture.witnessProgram(),
                        UTXO_AMOUNT,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void wrongPublicKeyMustFail() {

        Fixture fixture =
                createFixture();

        PrivateKey anotherPrivateKey =
                new PrivateKey(
                        BigInteger.valueOf(2)
                );

        byte[] anotherPublicKey =
                Secp256k1.publicKey(
                        anotherPrivateKey
                ).compressed();

        Witness witness =
                new Witness(
                        List.of(
                                fixture.witness().item(0),
                                anotherPublicKey
                        )
                );

        assertFalse(
                WitnessV0ScriptVerifier.verifyP2wpkh(
                        fixture.transaction(),
                        0,
                        new byte[0],
                        witness,
                        fixture.witnessProgram(),
                        UTXO_AMOUNT,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void modifiedSignatureMustFail() {

        Fixture fixture =
                createFixture();

        byte[] signature =
                fixture.witness()
                        .item(0);

        /*
         * Меняем байт внутри DER payload,
         * но не последний sighash byte.
         *
         * Результатом может стать либо другая
         * валидная DER-подпись, либо malformed DER.
         * В обоих случаях spend обязан провалиться.
         */
        signature[
                signature.length - 2
                ] ^= 0x01;

        Witness witness =
                new Witness(
                        List.of(
                                signature,
                                fixture.witness()
                                        .item(1)
                        )
                );

        assertFalse(
                WitnessV0ScriptVerifier.verifyP2wpkh(
                        fixture.transaction(),
                        0,
                        new byte[0],
                        witness,
                        fixture.witnessProgram(),
                        UTXO_AMOUNT,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void wrongWitnessProgramMustFail() {

        Fixture fixture =
                createFixture();

        byte[] wrongHash =
                fixture.publicKeyHash()
                        .clone();

        wrongHash[0] ^= 0x01;

        WitnessProgram wrongProgram =
                WitnessProgram.parse(
                        witnessScriptPubKey(
                                wrongHash
                        )
                ).orElseThrow();

        assertFalse(
                WitnessV0ScriptVerifier.verifyP2wpkh(
                        fixture.transaction(),
                        0,
                        new byte[0],
                        fixture.witness(),
                        wrongProgram,
                        UTXO_AMOUNT,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void generatedScriptCodeMustBeCanonicalP2pkh() {

        byte[] hash =
                new byte[20];

        Arrays.fill(
                hash,
                (byte) 0x11
        );

        byte[] actual =
                WitnessV0ScriptVerifier
                        .createP2wpkhScriptCode(
                                hash
                        );

        assertEquals(
                25,
                actual.length
        );

        assertEquals(
                Opcode.OP_DUP,
                Byte.toUnsignedInt(actual[0])
        );

        assertEquals(
                Opcode.OP_HASH160,
                Byte.toUnsignedInt(actual[1])
        );

        assertEquals(
                20,
                Byte.toUnsignedInt(actual[2])
        );

        assertArrayEquals(
                hash,
                Arrays.copyOfRange(
                        actual,
                        3,
                        23
                )
        );

        assertEquals(
                Opcode.OP_EQUALVERIFY,
                Byte.toUnsignedInt(actual[23])
        );

        assertEquals(
                Opcode.OP_CHECKSIG,
                Byte.toUnsignedInt(actual[24])
        );
    }

    private static Fixture createFixture() {

        /*
         * Фиксированный private key делает тест
         * полностью воспроизводимым.
         */
        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        PublicKey publicKey =
                Secp256k1.publicKey(
                        privateKey
                );

        byte[] publicKeyBytes =
                publicKey.compressed();

        byte[] publicKeyHash =
                Hash160.hash(
                        publicKeyBytes
                );

        WitnessProgram witnessProgram =
                WitnessProgram.parse(
                        witnessScriptPubKey(
                                publicKeyHash
                        )
                ).orElseThrow();

        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(
                                filled(
                                        32,
                                        (byte) 0x11
                                )
                        ),
                        new UInt32(0L)
                );

        /*
         * ВАЖНО:
         *
         * Сначала строим transaction без witness.
         * Witness не входит в BIP143 sighash.
         */
        TxIn unsignedInput =
                new TxIn(
                        previousOutput,
                        new byte[0],
                        new UInt32(
                                0xffff_fffeL
                        )
                );

        TxOut output =
                new TxOut(
                        OUTPUT_AMOUNT,
                        new byte[]{
                                (byte) Opcode.OP_1
                        }
                );

        Transaction unsignedTransaction =
                new Transaction(
                        2,
                        List.of(
                                unsignedInput
                        ),
                        List.of(
                                output
                        ),
                        new UInt32(0L)
                );

        byte[] scriptCode =
                WitnessV0ScriptVerifier
                        .createP2wpkhScriptCode(
                                publicKeyHash
                        );

        byte[] digest =
                WitnessV0SignatureHash.calculate(
                        unsignedTransaction,
                        0,
                        scriptCode,
                        UTXO_AMOUNT,
                        SignatureHashType.SIGHASH_ALL
                );

        EcdsaSignature signature =
                Secp256k1.sign(
                        digest,
                        privateKey
                );

        byte[] der =
                signature.toDer();

        byte[] signatureWithHashType =
                Arrays.copyOf(
                        der,
                        der.length + 1
                );

        signatureWithHashType[
                signatureWithHashType.length - 1
                ] =
                (byte)
                        SignatureHashType.SIGHASH_ALL;

        Witness witness =
                new Witness(
                        List.of(
                                signatureWithHashType,
                                publicKeyBytes
                        )
                );

        /*
         * Теперь создаём реальный transaction
         * с тем же input, но уже с witness.
         *
         * BIP143 digest останется тем же:
         * witness не является частью preimage.
         */
        TxIn signedInput =
                new TxIn(
                        previousOutput,
                        new byte[0],
                        new UInt32(
                                0xffff_fffeL
                        ),
                        witness
                );

        Transaction signedTransaction =
                new Transaction(
                        2,
                        List.of(
                                signedInput
                        ),
                        List.of(
                                output
                        ),
                        new UInt32(0L)
                );

        return new Fixture(
                signedTransaction,
                witness,
                witnessProgram,
                publicKeyHash
        );
    }

    private static byte[] witnessScriptPubKey(
            byte[] publicKeyHash
    ) {
        if (publicKeyHash.length != 20) {
            throw new IllegalArgumentException(
                    "publicKeyHash must contain 20 bytes"
            );
        }

        byte[] script =
                new byte[22];

        script[0] =
                (byte) Opcode.OP_0;

        script[1] =
                20;

        System.arraycopy(
                publicKeyHash,
                0,
                script,
                2,
                20
        );

        return script;
    }

    private static byte[] filled(
            int length,
            byte value
    ) {
        byte[] result =
                new byte[length];

        Arrays.fill(
                result,
                value
        );

        return result;
    }

    private record Fixture(
            Transaction transaction,
            Witness witness,
            WitnessProgram witnessProgram,
            byte[] publicKeyHash
    ) {

        private Fixture {
            publicKeyHash =
                    publicKeyHash.clone();
        }

        @Override
        public byte[] publicKeyHash() {
            return publicKeyHash.clone();
        }
    }
    @Test
    void witnessFlagMustBeRequired() {

        Fixture fixture =
                createFixture();

        assertFalse(
                WitnessV0ScriptVerifier.verifyP2wpkh(
                        fixture.transaction(),
                        0,
                        new byte[0],
                        fixture.witness(),
                        fixture.witnessProgram(),
                        UTXO_AMOUNT,
                        ScriptVerifyFlags.NONE
                )
        );
    }
}