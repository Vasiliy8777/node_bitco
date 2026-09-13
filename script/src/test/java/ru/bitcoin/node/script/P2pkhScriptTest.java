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

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class P2pkhScriptTest {

    @Test
    void validP2pkhSpendShouldPass() {

        Fixture fixture =
                createFixture();

        assertTrue(
                LegacyScriptVerifier.verify(
                        fixture.signedTransaction(),
                        0,
                        fixture.scriptSig(),
                        fixture.scriptPubKey()
                )
        );
    }

    @Test
    void wrongSignatureShouldFail() {

        Fixture fixture =
                createFixture();

        byte[] badSignature =
                fixture.signatureWithHashType();

        /*
         * Меняем байт внутри DER-подписи,
         * но оставляем hashType последним байтом.
         */
        badSignature[
                badSignature.length - 2
                ] ^= 0x01;

        byte[] badScriptSig =
                P2pkhScript.scriptSig(
                        badSignature,
                        fixture.publicKeyBytes()
                );

        assertFalse(
                LegacyScriptVerifier.verify(
                        fixture.signedTransaction(),
                        0,
                        badScriptSig,
                        fixture.scriptPubKey()
                )
        );
    }

    @Test
    void wrongPublicKeyShouldFailAtPubKeyHashCheck() {

        Fixture fixture =
                createFixture();

        PrivateKey wrongPrivateKey =
                new PrivateKey(
                        BigInteger.valueOf(2)
                );

        PublicKey wrongPublicKey =
                Secp256k1.publicKey(
                        wrongPrivateKey
                );

        byte[] badScriptSig =
                P2pkhScript.scriptSig(
                        fixture.signatureWithHashType(),
                        wrongPublicKey.compressed()
                );

        assertFalse(
                LegacyScriptVerifier.verify(
                        fixture.signedTransaction(),
                        0,
                        badScriptSig,
                        fixture.scriptPubKey()
                )
        );
    }

    @Test
    void wrongPubKeyHashShouldFail() {

        Fixture fixture =
                createFixture();

        byte[] wrongHash =
                Hash160.hash(
                        fixture.publicKeyBytes()
                );

        wrongHash[0] ^= 0x01;

        byte[] wrongScriptPubKey =
                P2pkhScript.scriptPubKey(
                        wrongHash
                );

        assertFalse(
                LegacyScriptVerifier.verify(
                        fixture.signedTransaction(),
                        0,
                        fixture.scriptSig(),
                        wrongScriptPubKey
                )
        );
    }

    @Test
    void modifyingTransactionOutputShouldInvalidateSignature() {

        Fixture fixture =
                createFixture();

        Transaction changedTransaction =
                new Transaction(
                        fixture.signedTransaction()
                                .version(),

                        fixture.signedTransaction()
                                .inputs(),

                        List.of(
                                new TxOut(
                                        89_999L,
                                        new byte[]{
                                                (byte) Opcode.OP_1
                                        }
                                )
                        ),

                        fixture.signedTransaction()
                                .lockTime()
                );

        assertFalse(
                LegacyScriptVerifier.verify(
                        changedTransaction,
                        0,
                        fixture.scriptSig(),
                        fixture.scriptPubKey()
                )
        );
    }

    @Test
    void emptyScriptSigShouldFail() {

        Fixture fixture =
                createFixture();

        assertFalse(
                LegacyScriptVerifier.verify(
                        fixture.signedTransaction(),
                        0,
                        new byte[0],
                        fixture.scriptPubKey()
                )
        );
    }

    @Test
    void p2pkhScriptPubKeyMustHaveCorrectStructure() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        PublicKey publicKey =
                Secp256k1.publicKey(
                        privateKey
                );

        byte[] hash =
                Hash160.hash(
                        publicKey.compressed()
                );

        byte[] script =
                P2pkhScript.scriptPubKey(
                        hash
                );

        assertEquals(
                25,
                script.length
        );

        assertEquals(
                Opcode.OP_DUP,
                Byte.toUnsignedInt(
                        script[0]
                )
        );

        assertEquals(
                Opcode.OP_HASH160,
                Byte.toUnsignedInt(
                        script[1]
                )
        );

        assertEquals(
                20,
                Byte.toUnsignedInt(
                        script[2]
                )
        );

        assertArrayEquals(
                hash,
                Arrays.copyOfRange(
                        script,
                        3,
                        23
                )
        );

        assertEquals(
                Opcode.OP_EQUALVERIFY,
                Byte.toUnsignedInt(
                        script[23]
                )
        );

        assertEquals(
                Opcode.OP_CHECKSIG,
                Byte.toUnsignedInt(
                        script[24]
                )
        );
    }

    private static Fixture createFixture() {

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

        byte[] scriptPubKey =
                P2pkhScript.scriptPubKey(
                        publicKeyHash
                );

        /*
         * Предыдущий UTXO условно содержит именно
         * этот P2PKH scriptPubKey.
         */
        byte[] previousTransactionHash =
                new byte[32];

        previousTransactionHash[0] =
                0x44;

        /*
         * Сначала создаём unsigned transaction.
         *
         * Для legacy sighash содержимое scriptSig
         * исходного input здесь не используется:
         * LegacySignatureHash подставит scriptPubKey
         * предыдущего UTXO как scriptCode.
         */
        Transaction unsignedTransaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        new OutPoint(
                                                new Hash256(
                                                        previousTransactionHash
                                                ),
                                                new UInt32(0)
                                        ),
                                        new byte[0],
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        90_000L,
                                        new byte[]{
                                                (byte) Opcode.OP_1
                                        }
                                )
                        ),
                        new UInt32(0)
                );

        int hashType =
                SignatureHashType.SIGHASH_ALL;

        byte[] digest =
                LegacySignatureHash.calculate(
                        unsignedTransaction,
                        0,
                        scriptPubKey,
                        hashType
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
                (byte) hashType;

        byte[] scriptSig =
                P2pkhScript.scriptSig(
                        signatureWithHashType,
                        publicKeyBytes
                );

        /*
         * Теперь формируем настоящую подписанную
         * transaction с scriptSig внутри input.
         */
        Transaction signedTransaction =
                new Transaction(
                        unsignedTransaction.version(),

                        List.of(
                                new TxIn(
                                        unsignedTransaction
                                                .inputs()
                                                .get(0)
                                                .previousOutput(),

                                        scriptSig,

                                        unsignedTransaction
                                                .inputs()
                                                .get(0)
                                                .sequence()
                                )
                        ),

                        unsignedTransaction.outputs(),

                        unsignedTransaction.lockTime()
                );

        return new Fixture(
                signedTransaction,
                scriptPubKey,
                scriptSig,
                signatureWithHashType,
                publicKeyBytes
        );
    }

    private record Fixture(
            Transaction signedTransaction,
            byte[] scriptPubKey,
            byte[] scriptSig,
            byte[] signatureWithHashType,
            byte[] publicKeyBytes
    ) {

        private Fixture {

            scriptPubKey =
                    scriptPubKey.clone();

            scriptSig =
                    scriptSig.clone();

            signatureWithHashType =
                    signatureWithHashType.clone();

            publicKeyBytes =
                    publicKeyBytes.clone();
        }

        @Override
        public byte[] scriptPubKey() {
            return scriptPubKey.clone();
        }

        @Override
        public byte[] scriptSig() {
            return scriptSig.clone();
        }

        @Override
        public byte[] signatureWithHashType() {
            return signatureWithHashType.clone();
        }

        @Override
        public byte[] publicKeyBytes() {
            return publicKeyBytes.clone();
        }
    }
}