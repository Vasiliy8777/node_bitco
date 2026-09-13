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

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyP2shVerifierTest {

    @Test
    void validP2shRedeemScriptShouldPass() {

        byte[] redeemScript =
                new byte[]{
                        (byte) Opcode.OP_1
                };

        byte[] scriptPubKey =
                p2shScriptPubKey(
                        Hash160.hash(
                                redeemScript
                        )
                );

        byte[] scriptSig =
                push(
                        redeemScript
                );

        Transaction transaction =
                transactionWithScriptSig(
                        scriptSig
                );

        assertTrue(
                LegacyScriptVerifier.verify(
                        transaction,
                        0,
                        scriptSig,
                        scriptPubKey,
                        ScriptVerifyFlags.P2SH
                )
        );
    }

    @Test
    void redeemScriptReturningFalseShouldFail() {

        byte[] redeemScript =
                new byte[]{
                        (byte) Opcode.OP_0
                };

        byte[] scriptPubKey =
                p2shScriptPubKey(
                        Hash160.hash(
                                redeemScript
                        )
                );

        byte[] scriptSig =
                push(
                        redeemScript
                );

        Transaction transaction =
                transactionWithScriptSig(
                        scriptSig
                );

        assertFalse(
                LegacyScriptVerifier.verify(
                        transaction,
                        0,
                        scriptSig,
                        scriptPubKey,
                        ScriptVerifyFlags.P2SH
                )
        );
    }

    @Test
    void wrongRedeemScriptHashShouldFail() {

        byte[] redeemScript =
                new byte[]{
                        (byte) Opcode.OP_1
                };

        byte[] differentRedeemScript =
                new byte[]{
                        (byte) Opcode.OP_0
                };

        byte[] scriptPubKey =
                p2shScriptPubKey(
                        Hash160.hash(
                                differentRedeemScript
                        )
                );

        byte[] scriptSig =
                push(
                        redeemScript
                );

        Transaction transaction =
                transactionWithScriptSig(
                        scriptSig
                );

        assertFalse(
                LegacyScriptVerifier.verify(
                        transaction,
                        0,
                        scriptSig,
                        scriptPubKey,
                        ScriptVerifyFlags.P2SH
                )
        );
    }

    @Test
    void nonPushOnlyScriptSigMustFailWhenP2shEnabled() {

        byte[] redeemScript =
                new byte[]{
                        (byte) Opcode.OP_1
                };

        byte[] scriptPubKey =
                p2shScriptPubKey(
                        Hash160.hash(
                                redeemScript
                        )
                );

        /*
         * OP_1
         * OP_DROP
         * <redeemScript>
         *
         * Скрипт мог бы оставить redeemScript
         * на stack, но BIP16 требует scriptSig
         * push-only.
         */
        byte[] scriptSig =
                new byte[]{
                        (byte) Opcode.OP_1,
                        (byte) Opcode.OP_DROP,
                        0x01,
                        (byte) Opcode.OP_1
                };

        Transaction transaction =
                transactionWithScriptSig(
                        scriptSig
                );

        assertFalse(
                LegacyScriptVerifier.verify(
                        transaction,
                        0,
                        scriptSig,
                        scriptPubKey,
                        ScriptVerifyFlags.P2SH
                )
        );
    }

    @Test
    void p2shRulesMustNotApplyWhenFlagDisabled() {

        byte[] redeemScript =
                new byte[]{
                        (byte) Opcode.OP_0
                };

        byte[] scriptPubKey =
                p2shScriptPubKey(
                        Hash160.hash(
                                redeemScript
                        )
                );

        byte[] scriptSig =
                push(
                        redeemScript
                );

        Transaction transaction =
                transactionWithScriptSig(
                        scriptSig
                );

        /*
         * Без P2SH flag выполняются только:
         *
         * scriptSig
         * scriptPubKey
         *
         * HASH совпадает => scriptPubKey true.
         *
         * redeemScript OP_0 НЕ выполняется.
         */
        assertTrue(
                LegacyScriptVerifier.verify(
                        transaction,
                        0,
                        scriptSig,
                        scriptPubKey,
                        ScriptVerifyFlags.NONE
                )
        );
    }

    private static Transaction transactionWithScriptSig(
            byte[] scriptSig
    ) {

        byte[] previousHash =
                new byte[32];

        previousHash[0] =
                0x31;

        return new Transaction(
                1,
                List.of(
                        new TxIn(
                                new OutPoint(
                                        new Hash256(
                                                previousHash
                                        ),
                                        new UInt32(0)
                                ),
                                scriptSig,
                                TxIn.FINAL_SEQUENCE
                        )
                ),
                List.of(
                        new TxOut(
                                1_000L,
                                new byte[]{
                                        (byte) Opcode.OP_1
                                }
                        )
                ),
                new UInt32(0)
        );
    }

    private static byte[] p2shScriptPubKey(
            byte[] hash
    ) {

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.write(
                Opcode.OP_HASH160
        );

        out.write(
                20
        );

        out.writeBytes(
                hash
        );

        out.write(
                Opcode.OP_EQUAL
        );

        return out.toByteArray();
    }

    private static byte[] push(
            byte[] data
    ) {

        if (data.length > Opcode.OP_DATA_MAX) {
            throw new IllegalArgumentException(
                    "Test helper supports direct pushes only"
            );
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.write(
                data.length
        );

        out.writeBytes(
                data
        );

        return out.toByteArray();
    }
    @Test
    void p2shP2pkhWithRealSignatureShouldPass() {

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

        /*
         * redeemScript:
         *
         * DUP
         * HASH160
         * <pubKeyHash>
         * EQUALVERIFY
         * CHECKSIG
         */
        byte[] redeemScript =
                P2pkhScript.scriptPubKey(
                        Hash160.hash(
                                publicKeyBytes
                        )
                );

        /*
         * UTXO scriptPubKey:
         *
         * HASH160
         * <HASH160(redeemScript)>
         * EQUAL
         */
        byte[] scriptPubKey =
                p2shScriptPubKey(
                        Hash160.hash(
                                redeemScript
                        )
                );

        byte[] previousHash =
                new byte[32];

        previousHash[0] =
                0x41;

        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(
                                previousHash
                        ),
                        new UInt32(0)
                );

        Transaction unsignedTransaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        previousOutput,
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

        /*
         * Критично:
         *
         * signature hash считается относительно
         * redeemScript, а НЕ внешнего P2SH scriptPubKey.
         */
        byte[] digest =
                LegacySignatureHash.calculate(
                        unsignedTransaction,
                        0,
                        redeemScript,
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
                (byte) SignatureHashType.SIGHASH_ALL;

        /*
         * P2SH-P2PKH scriptSig:
         *
         * <signature>
         * <publicKey>
         * <redeemScript>
         */
        byte[] scriptSig =
                concatenate(
                        push(signatureWithHashType),
                        push(publicKeyBytes),
                        push(redeemScript)
                );

        Transaction signedTransaction =
                new Transaction(
                        unsignedTransaction.version(),
                        List.of(
                                new TxIn(
                                        previousOutput,
                                        scriptSig,
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        unsignedTransaction.outputs(),
                        unsignedTransaction.lockTime()
                );

        assertTrue(
                LegacyScriptVerifier.verify(
                        signedTransaction,
                        0,
                        scriptSig,
                        scriptPubKey,
                        ScriptVerifyFlags.P2SH
                                | ScriptVerifyFlags.DERSIG
                )
        );
    }
    private static byte[] concatenate(
            byte[]... arrays
    ) {

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        for (byte[] array : arrays) {
            out.writeBytes(
                    array
            );
        }

        return out.toByteArray();
    }
    @Test
    void p2shP2pkhMustNotUseOuterScriptPubKeyForSignatureHash() {

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

        byte[] redeemScript =
                P2pkhScript.scriptPubKey(
                        Hash160.hash(
                                publicKeyBytes
                        )
                );

        byte[] scriptPubKey =
                p2shScriptPubKey(
                        Hash160.hash(
                                redeemScript
                        )
                );

        byte[] previousHash =
                new byte[32];

        previousHash[0] =
                0x42;

        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(
                                previousHash
                        ),
                        new UInt32(0)
                );

        Transaction unsignedTransaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        previousOutput,
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

        /*
         * Намеренно НЕПРАВИЛЬНО:
         *
         * подписываем внешний P2SH scriptPubKey.
         */
        byte[] wrongDigest =
                LegacySignatureHash.calculate(
                        unsignedTransaction,
                        0,
                        scriptPubKey,
                        SignatureHashType.SIGHASH_ALL
                );

        EcdsaSignature signature =
                Secp256k1.sign(
                        wrongDigest,
                        privateKey
                );

        byte[] signatureWithHashType =
                Arrays.copyOf(
                        signature.toDer(),
                        signature.toDer().length + 1
                );

        signatureWithHashType[
                signatureWithHashType.length - 1
                ] =
                (byte) SignatureHashType.SIGHASH_ALL;

        byte[] scriptSig =
                concatenate(
                        push(signatureWithHashType),
                        push(publicKeyBytes),
                        push(redeemScript)
                );

        Transaction signedTransaction =
                new Transaction(
                        unsignedTransaction.version(),
                        List.of(
                                new TxIn(
                                        previousOutput,
                                        scriptSig,
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        unsignedTransaction.outputs(),
                        unsignedTransaction.lockTime()
                );

        assertFalse(
                LegacyScriptVerifier.verify(
                        signedTransaction,
                        0,
                        scriptSig,
                        scriptPubKey,
                        ScriptVerifyFlags.P2SH
                                | ScriptVerifyFlags.DERSIG
                )
        );
    }
}