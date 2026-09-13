package ru.bitcoin.node.consensus.transaction;

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
import ru.bitcoin.node.script.Opcode;
import ru.bitcoin.node.script.ScriptVerifyFlags;
import ru.bitcoin.node.script.SignatureHashType;
import ru.bitcoin.node.script.WitnessV0SignatureHash;
import ru.bitcoin.node.script.WitnessV0ScriptVerifier;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputScriptValidatorP2wpkhTest {

    private static final long UTXO_AMOUNT =
            100_000L;

    @Test
    void validNativeP2wpkhMustPass() {

        Fixture fixture =
                createFixture();

        assertDoesNotThrow(
                () -> InputScriptValidator.validate(
                        fixture.transaction(),
                        0,
                        fixture.utxoView(),
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void wrongAmountMustFail() {

        Fixture fixture =
                createFixture();

        UtxoEntry wrongUtxo =
                new UtxoEntry(
                        UTXO_AMOUNT + 1,
                        fixture.scriptPubKey(),
                        100,
                        false
                );

        UtxoView view =
                outPoint ->
                        Optional.of(
                                wrongUtxo
                        );

        assertThrows(
                TransactionValidationException.class,
                () -> InputScriptValidator.validate(
                        fixture.transaction(),
                        0,
                        view,
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void modifiedWitnessSignatureMustFail() {

        Fixture fixture =
                createFixture();

        byte[] signature =
                fixture.transaction()
                        .inputs()
                        .getFirst()
                        .witness()
                        .item(0);

        signature[
                signature.length - 2
                ] ^= 0x01;

        Witness badWitness =
                new Witness(
                        List.of(
                                signature,
                                fixture.transaction()
                                        .inputs()
                                        .getFirst()
                                        .witness()
                                        .item(1)
                        )
                );

        TxIn oldInput =
                fixture.transaction()
                        .inputs()
                        .getFirst();

        TxIn badInput =
                new TxIn(
                        oldInput.previousOutput(),
                        oldInput.scriptSig(),
                        oldInput.sequence(),
                        badWitness
                );

        Transaction badTransaction =
                new Transaction(
                        fixture.transaction().version(),
                        List.of(badInput),
                        fixture.transaction().outputs(),
                        fixture.transaction().lockTime()
                );

        assertThrows(
                TransactionValidationException.class,
                () -> InputScriptValidator.validate(
                        badTransaction,
                        0,
                        fixture.utxoView(),
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void nativeP2wpkhWithNonEmptyScriptSigMustFail() {

        Fixture fixture =
                createFixture();

        TxIn oldInput =
                fixture.transaction()
                        .inputs()
                        .getFirst();

        TxIn badInput =
                new TxIn(
                        oldInput.previousOutput(),
                        new byte[]{0x00},
                        oldInput.sequence(),
                        oldInput.witness()
                );

        Transaction badTransaction =
                new Transaction(
                        fixture.transaction().version(),
                        List.of(badInput),
                        fixture.transaction().outputs(),
                        fixture.transaction().lockTime()
                );

        assertThrows(
                TransactionValidationException.class,
                () -> InputScriptValidator.validate(
                        badTransaction,
                        0,
                        fixture.utxoView(),
                        ScriptVerifyFlags.WITNESS
                )
        );
    }

    @Test
    void witnessOnLegacyInputMustFailWhenWitnessRulesActive() {

        Fixture fixture =
                createFixture();

        byte[] legacyAnyoneCanSpend =
                new byte[]{
                        (byte) Opcode.OP_1
                };

        UtxoEntry legacyUtxo =
                new UtxoEntry(
                        UTXO_AMOUNT,
                        legacyAnyoneCanSpend,
                        100,
                        false
                );

        UtxoView view =
                outPoint ->
                        Optional.of(
                                legacyUtxo
                        );

        /*
         * Legacy OP_1 itself succeeds,
         * but unexpected witness must make
         * the input invalid.
         */
        assertThrows(
                TransactionValidationException.class,
                () -> InputScriptValidator.validate(
                        fixture.transaction(),
                        0,
                        view,
                        ScriptVerifyFlags.WITNESS
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
                witnessScriptPubKey(
                        publicKeyHash
                );

        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(
                                filled(
                                        32,
                                        (byte) 0x55
                                )
                        ),
                        new UInt32(0L)
                );

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
                        90_000L,
                        new byte[]{
                                (byte) Opcode.OP_1
                        }
                );

        Transaction unsignedTransaction =
                new Transaction(
                        2,
                        List.of(unsignedInput),
                        List.of(output),
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
                        List.of(signedInput),
                        List.of(output),
                        new UInt32(0L)
                );

        UtxoEntry utxo =
                new UtxoEntry(
                        UTXO_AMOUNT,
                        scriptPubKey,
                        100,
                        false
                );

        UtxoView view =
                outPoint -> {
                    if (previousOutput.equals(
                            outPoint
                    )) {
                        return Optional.of(
                                utxo
                        );
                    }

                    return Optional.empty();
                };

        return new Fixture(
                signedTransaction,
                view,
                scriptPubKey
        );
    }

    private static byte[] witnessScriptPubKey(
            byte[] publicKeyHash
    ) {
        byte[] result =
                new byte[22];

        result[0] =
                (byte) Opcode.OP_0;

        result[1] =
                20;

        System.arraycopy(
                publicKeyHash,
                0,
                result,
                2,
                20
        );

        return result;
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
            UtxoView utxoView,
            byte[] scriptPubKey
    ) {

        private Fixture {
            scriptPubKey =
                    scriptPubKey.clone();
        }

        @Override
        public byte[] scriptPubKey() {
            return scriptPubKey.clone();
        }
    }
}