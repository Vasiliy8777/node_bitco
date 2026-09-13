package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
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

class CheckSigTest {

    @Test
    void validSignatureShouldPushTrue() {

        Fixture fixture =
                createFixture();

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                fixture.signatureWithHashType()
        );

        machine.push(
                fixture.publicKey().compressed()
        );

        ScriptInterpreter.execute(
                new byte[]{
                        (byte) Opcode.OP_CHECKSIG
                },
                machine,
                fixture.context()
        );

        assertEquals(
                1,
                machine.size()
        );

        assertTrue(
                ScriptNumber.castToBool(
                        machine.pop()
                )
        );
    }

    @Test
    void wrongPublicKeyShouldPushFalse() {

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

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                fixture.signatureWithHashType()
        );

        machine.push(
                wrongPublicKey.compressed()
        );

        ScriptInterpreter.execute(
                new byte[]{
                        (byte) Opcode.OP_CHECKSIG
                },
                machine,
                fixture.context()
        );

        assertFalse(
                ScriptNumber.castToBool(
                        machine.pop()
                )
        );
    }

    @Test
    void changedTransactionShouldMakeSignatureInvalid() {

        Fixture fixture =
                createFixture();

        Transaction changedTransaction =
                new Transaction(
                        fixture.transaction().version(),
                        fixture.transaction().inputs(),
                        List.of(
                                new TxOut(
                                        49_999L,
                                        new byte[]{
                                                (byte) Opcode.OP_1
                                        }
                                )
                        ),
                        fixture.transaction().lockTime()
                );

        ScriptExecutionContext changedContext =
                new ScriptExecutionContext(
                        changedTransaction,
                        0,
                        fixture.scriptCode()
                );

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                fixture.signatureWithHashType()
        );

        machine.push(
                fixture.publicKey().compressed()
        );

        ScriptInterpreter.execute(
                new byte[]{
                        (byte) Opcode.OP_CHECKSIG
                },
                machine,
                changedContext
        );

        assertFalse(
                ScriptNumber.castToBool(
                        machine.pop()
                )
        );
    }

    @Test
    void emptySignatureShouldPushFalse() {

        Fixture fixture =
                createFixture();

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                new byte[0]
        );

        machine.push(
                fixture.publicKey().compressed()
        );

        ScriptInterpreter.execute(
                new byte[]{
                        (byte) Opcode.OP_CHECKSIG
                },
                machine,
                fixture.context()
        );

        assertFalse(
                ScriptNumber.castToBool(
                        machine.pop()
                )
        );
    }

    @Test
    void malformedDerShouldPushFalse() {

        Fixture fixture =
                createFixture();

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * 01 02 03 = invalid DER
         * 01       = SIGHASH_ALL
         */
        machine.push(
                new byte[]{
                        0x01,
                        0x02,
                        0x03,
                        (byte) SignatureHashType.SIGHASH_ALL
                }
        );

        machine.push(
                fixture.publicKey().compressed()
        );

        ScriptInterpreter.execute(
                new byte[]{
                        (byte) Opcode.OP_CHECKSIG
                },
                machine,
                fixture.context()
        );

        assertFalse(
                ScriptNumber.castToBool(
                        machine.pop()
                )
        );
    }

    @Test
    void invalidPublicKeyShouldPushFalse() {

        Fixture fixture =
                createFixture();

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                fixture.signatureWithHashType()
        );

        machine.push(
                new byte[]{
                        0x01,
                        0x02,
                        0x03
                }
        );

        ScriptInterpreter.execute(
                new byte[]{
                        (byte) Opcode.OP_CHECKSIG
                },
                machine,
                fixture.context()
        );

        assertFalse(
                ScriptNumber.castToBool(
                        machine.pop()
                )
        );
    }

    @Test
    void checkSigWithoutContextShouldFail() {

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                new byte[]{0x01}
        );

        machine.push(
                new byte[]{0x02}
        );

        assertThrows(
                ScriptExecutionException.class,
                () -> ScriptInterpreter.execute(
                        new byte[]{
                                (byte) Opcode.OP_CHECKSIG
                        },
                        machine
                )
        );
    }

    @Test
    void checkSigVerifyShouldConsumeSuccessfulResult() {

        Fixture fixture =
                createFixture();

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                fixture.signatureWithHashType()
        );

        machine.push(
                fixture.publicKey().compressed()
        );

        ScriptInterpreter.execute(
                new byte[]{
                        (byte) Opcode.OP_CHECKSIGVERIFY
                },
                machine,
                fixture.context()
        );

        assertTrue(
                machine.isEmpty()
        );
    }

    @Test
    void checkSigVerifyShouldFailForInvalidSignature() {

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

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                fixture.signatureWithHashType()
        );

        machine.push(
                wrongPublicKey.compressed()
        );

        assertThrows(
                ScriptExecutionException.class,
                () -> ScriptInterpreter.execute(
                        new byte[]{
                                (byte) Opcode.OP_CHECKSIGVERIFY
                        },
                        machine,
                        fixture.context()
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

        byte[] previousTransactionHash =
                new byte[32];

        previousTransactionHash[0] =
                0x11;

        Transaction transaction =
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
                                        50_000L,
                                        new byte[]{
                                                (byte) Opcode.OP_1
                                        }
                                )
                        ),
                        new UInt32(0)
                );

        /*
         * Пока используем простой scriptCode.
         *
         * Следующим этапом соберём полноценный
         * P2PKH scriptPubKey.
         */
        byte[] scriptCode =
                new byte[]{
                        (byte) Opcode.OP_DUP,
                        (byte) Opcode.OP_HASH160,
                        0x14,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00,
                        0x00, 0x00, 0x00, 0x00,
                        (byte) Opcode.OP_EQUALVERIFY,
                        (byte) Opcode.OP_CHECKSIG
                };

        int hashType =
                SignatureHashType.SIGHASH_ALL;

        byte[] digest =
                LegacySignatureHash.calculate(
                        transaction,
                        0,
                        scriptCode,
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
                new byte[
                        der.length + 1
                        ];

        System.arraycopy(
                der,
                0,
                signatureWithHashType,
                0,
                der.length
        );

        signatureWithHashType[
                signatureWithHashType.length - 1
                ] =
                (byte) hashType;

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        scriptCode
                );

        return new Fixture(
                transaction,
                scriptCode,
                publicKey,
                signatureWithHashType,
                context
        );
    }

    private record Fixture(
            Transaction transaction,
            byte[] scriptCode,
            PublicKey publicKey,
            byte[] signatureWithHashType,
            ScriptExecutionContext context
    ) {

        private Fixture {

            scriptCode =
                    scriptCode.clone();

            signatureWithHashType =
                    signatureWithHashType.clone();
        }

        @Override
        public byte[] scriptCode() {
            return scriptCode.clone();
        }

        @Override
        public byte[] signatureWithHashType() {
            return signatureWithHashType.clone();
        }
    }
    @Test
    void checkSigShouldUseScriptAfterLastCodeSeparator() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        PublicKey publicKey =
                Secp256k1.publicKey(
                        privateKey
                );

        byte[] previousHash =
                new byte[32];

        previousHash[0] =
                0x55;

        Transaction transaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        new OutPoint(
                                                new Hash256(
                                                        previousHash
                                                ),
                                                new UInt32(0)
                                        ),
                                        new byte[0],
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        10_000L,
                                        new byte[]{
                                                (byte) Opcode.OP_1
                                        }
                                )
                        ),
                        new UInt32(0)
                );

        /*
         * Реально исполняемый script:
         *
         * OP_CODESEPARATOR
         * OP_CHECKSIG
         */
        byte[] executionScript =
                new byte[]{
                        (byte) Opcode.OP_CODESEPARATOR,
                        (byte) Opcode.OP_CHECKSIG
                };

        /*
         * CHECKSIG должен подписывать только suffix
         * ПОСЛЕ последнего CODESEPARATOR:
         *
         * OP_CHECKSIG
         */
        byte[] expectedScriptCode =
                new byte[]{
                        (byte) Opcode.OP_CHECKSIG
                };

        int hashType =
                SignatureHashType.SIGHASH_ALL;

        byte[] digest =
                LegacySignatureHash.calculate(
                        transaction,
                        0,
                        expectedScriptCode,
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

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                signatureWithHashType
        );

        machine.push(
                publicKey.compressed()
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        executionScript
                );

        ScriptInterpreter.execute(
                executionScript,
                machine,
                context
        );

        assertTrue(
                ScriptNumber.castToBool(
                        machine.pop()
                )
        );
    }
    /*@Test
    void checkSigMustNotUseBytesBeforeLastCodeSeparator() {

        PrivateKey privateKey =
                new PrivateKey(
                        BigInteger.ONE
                );

        PublicKey publicKey =
                Secp256k1.publicKey(
                        privateKey
                );

        byte[] previousHash =
                new byte[32];

        previousHash[0] =
                0x66;

        Transaction transaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        new OutPoint(
                                                new Hash256(
                                                        previousHash
                                                ),
                                                new UInt32(0)
                                        ),
                                        new byte[0],
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        10_000L,
                                        new byte[]{
                                                (byte) Opcode.OP_1
                                        }
                                )
                        ),
                        new UInt32(0)
                );

        byte[] executionScript =
                new byte[]{
                        (byte) Opcode.OP_CODESEPARATOR,
                        (byte) Opcode.OP_CHECKSIG
                };

        *//*
         * Намеренно НЕПРАВИЛЬНО подписываем весь script,
         * включая CODESEPARATOR.
         *
         * Interpreter при CHECKSIG использует только:
         *
         * OP_CHECKSIG
         *
         * поэтому verification должна провалиться.
         *//*
        byte[] wrongDigest =
                LegacySignatureHash.calculate(
                        transaction,
                        0,
                        executionScript,
                        SignatureHashType.SIGHASH_ALL
                );

        EcdsaSignature signature =
                Secp256k1.sign(
                        wrongDigest,
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

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                signatureWithHashType
        );

        machine.push(
                publicKey.compressed()
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction,
                        0,
                        executionScript
                );

        ScriptInterpreter.execute(
                executionScript,
                machine,
                context
        );

        assertFalse(
                ScriptNumber.castToBool(
                        machine.pop()
                )
        );
    }*/
    @Test
    void malformedDerShouldBecomeScriptFailureWhenDerSigEnabled() {

        Fixture fixture =
                createFixture();

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                new byte[]{
                        0x01,
                        0x02,
                        0x03,
                        (byte) SignatureHashType.SIGHASH_ALL
                }
        );

        machine.push(
                fixture.publicKey()
                        .compressed()
        );

        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        fixture.transaction(),
                        0,
                        fixture.scriptCode(),
                        ScriptVerifyFlags.DERSIG
                );

        assertThrows(
                ScriptExecutionException.class,
                () -> ScriptInterpreter.execute(
                        new byte[]{
                                (byte) Opcode.OP_CHECKSIG
                        },
                        machine,
                        context
                )
        );
    }
}