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
import ru.bitcoin.node.script.*;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputScriptValidatorTest {
    @Test
    void historicalDerTrailingBytesAreAllowedOnlyWithoutStrictDerFlags() {
        Fixture fixture = createFixture();
        byte[] original = fixture.signatureWithHashType();
        byte[] historical = Arrays.copyOf(original, original.length + 1);
        historical[original.length - 1] = 0;
        historical[original.length] = original[original.length - 1];
        var tx = replaceScriptSig(fixture.transaction(), P2pkhScript.scriptSig(historical, fixture.publicKey().compressed()));
        assertDoesNotThrow(() -> InputScriptValidator.validateAll(tx, fixture.utxoView(), ScriptVerifyFlags.NONE));
        assertThrows(TransactionValidationException.class, () -> InputScriptValidator.validateAll(tx, fixture.utxoView(), ScriptVerifyFlags.DERSIG));
    }

    @Test
    void validP2pkhInputShouldPass() {

        Fixture fixture =
                createFixture();

        assertDoesNotThrow(
                () -> InputScriptValidator.validateAll(
                        fixture.transaction(),
                        fixture.utxoView(),
                        ScriptVerifyFlags.NONE
                )
        );
    }

    @Test
    void wrongSignatureShouldFail() {

        Fixture fixture =
                createFixture();

        byte[] badSignature =
                fixture.signatureWithHashType();

        badSignature[
                badSignature.length - 2
                ] ^= 0x01;

        byte[] badScriptSig =
                P2pkhScript.scriptSig(
                        badSignature,
                        fixture.publicKey()
                                .compressed()
                );

        Transaction badTransaction =
                replaceScriptSig(
                        fixture.transaction(),
                        badScriptSig
                );

        assertThrows(
                TransactionValidationException.class,
                () -> InputScriptValidator.validateAll(
                        badTransaction,
                        fixture.utxoView(),
                        ScriptVerifyFlags.NONE
                )
        );
    }

    @Test
    void missingUtxoShouldFail() {

        Fixture fixture =
                createFixture();

        UtxoView emptyView =
                outPoint -> Optional.empty();

        assertThrows(
                TransactionValidationException.class,
                () -> InputScriptValidator.validateAll(
                        fixture.transaction(),
                        emptyView,
                        ScriptVerifyFlags.NONE
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

        byte[] publicKeyHash =
                Hash160.hash(
                        publicKey.compressed()
                );

        byte[] previousScriptPubKey =
                P2pkhScript.scriptPubKey(
                        publicKeyHash
                );

        byte[] previousHashBytes =
                new byte[32];

        previousHashBytes[0] =
                0x21;

        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(
                                previousHashBytes
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

        int hashType =
                SignatureHashType.SIGHASH_ALL;

        byte[] digest =
                LegacySignatureHash.calculate(
                        unsignedTransaction,
                        0,
                        previousScriptPubKey,
                        hashType
                );

        EcdsaSignature signature =
                Secp256k1.sign(
                        digest,
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
                (byte) hashType;

        byte[] scriptSig =
                P2pkhScript.scriptSig(
                        signatureWithHashType,
                        publicKey.compressed()
                );

        Transaction transaction =
                replaceScriptSig(
                        unsignedTransaction,
                        scriptSig
                );

        UtxoEntry utxo =
                new UtxoEntry(
                        100_000L,
                        previousScriptPubKey,
                        100L,
                        false
                );

        UtxoView utxoView =
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
                transaction,
                publicKey,
                signatureWithHashType,
                utxoView
        );
    }

    private static Transaction replaceScriptSig(
            Transaction transaction,
            byte[] scriptSig
    ) {

        TxIn original =
                transaction.inputs()
                        .get(0);

        TxIn replacement =
                new TxIn(
                        original.previousOutput(),
                        scriptSig,
                        original.sequence()
                );

        return new Transaction(
                transaction.version(),
                List.of(
                        replacement
                ),
                transaction.outputs(),
                transaction.lockTime()
        );
    }

    private record Fixture(
            Transaction transaction,
            PublicKey publicKey,
            byte[] signatureWithHashType,
            UtxoView utxoView
    ) {

        private Fixture {
            signatureWithHashType =
                    signatureWithHashType.clone();
        }

        @Override
        public byte[] signatureWithHashType() {
            return signatureWithHashType.clone();
        }
    }
    private static byte[] cltvScript(
            long lockTime
    ) {

        byte[] encoded =
                ScriptNumber.encode(
                        lockTime
                );

        byte[] script =
                new byte[
                        1
                                + encoded.length
                                + 3
                        ];

        int offset = 0;

        /*
         * <lockTime>
         */
        script[offset++] =
                (byte) encoded.length;

        System.arraycopy(
                encoded,
                0,
                script,
                offset,
                encoded.length
        );

        offset +=
                encoded.length;

        /*
         * OP_CHECKLOCKTIMEVERIFY
         * OP_DROP
         * OP_1
         */
        script[offset++] =
                (byte) Opcode.OP_CHECKLOCKTIMEVERIFY;

        script[offset++] =
                (byte) Opcode.OP_DROP;

        script[offset] =
                (byte) Opcode.OP_1;

        return script;
    }
    @Test
    void cltvUtxoMustValidateWhenLockTimeRequirementIsSatisfied() {

        OutPoint outPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "22".repeat(32)
                        ),
                        new UInt32(0)
                );

        byte[] scriptPubKey =
                cltvScript(
                        500L
                );

        Transaction transaction =
                new Transaction(
                        2,
                        List.of(
                                new TxIn(
                                        outPoint,
                                        new byte[0],
                                        new UInt32(
                                                0xffff_fffeL
                                        )
                                )
                        ),
                        List.of(
                                new TxOut(
                                        900L,
                                        new byte[]{
                                                (byte) Opcode.OP_1
                                        }
                                )
                        ),
                        new UInt32(
                                500L
                        )
                );

        UtxoView utxoView =
                requestedOutPoint -> {

                    if (!requestedOutPoint.equals(
                            outPoint
                    )) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new UtxoEntry(
                                    1_000L,
                                    scriptPubKey,
                                    100L,
                                    false
                            )
                    );
                };

        assertDoesNotThrow(
                () ->
                        InputScriptValidator.validate(
                                transaction,
                                0,
                                utxoView,
                                ScriptVerifyFlags
                                        .CHECKLOCKTIMEVERIFY
                        )
        );
    }
    @Test
    void cltvUtxoMustFailWhenTransactionLockTimeIsTooSmall() {

        OutPoint outPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "33".repeat(32)
                        ),
                        new UInt32(0)
                );

        byte[] scriptPubKey =
                cltvScript(
                        500L
                );

        Transaction transaction =
                new Transaction(
                        2,
                        List.of(
                                new TxIn(
                                        outPoint,
                                        new byte[0],
                                        new UInt32(
                                                0xffff_fffeL
                                        )
                                )
                        ),
                        List.of(
                                new TxOut(
                                        900L,
                                        new byte[]{
                                                (byte) Opcode.OP_1
                                        }
                                )
                        ),
                        new UInt32(
                                499L
                        )
                );

        UtxoView utxoView =
                requestedOutPoint -> {

                    if (!requestedOutPoint.equals(
                            outPoint
                    )) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new UtxoEntry(
                                    1_000L,
                                    scriptPubKey,
                                    100L,
                                    false
                            )
                    );
                };

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                transaction,
                                0,
                                utxoView,
                                ScriptVerifyFlags
                                        .CHECKLOCKTIMEVERIFY
                        )
        );
    }
    @Test
    void cltvOpcodeMustBehaveAsNopWhenConsensusFlagIsDisabled() {

        OutPoint outPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "44".repeat(32)
                        ),
                        new UInt32(0)
                );

        byte[] scriptPubKey =
                cltvScript(
                        500L
                );

        /*
         * nLockTime намеренно НЕ удовлетворяет CLTV.
         */
        Transaction transaction =
                new Transaction(
                        2,
                        List.of(
                                new TxIn(
                                        outPoint,
                                        new byte[0],
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        900L,
                                        new byte[]{
                                                (byte) Opcode.OP_1
                                        }
                                )
                        ),
                        new UInt32(
                                0L
                        )
                );

        UtxoView utxoView =
                requestedOutPoint -> {

                    if (!requestedOutPoint.equals(
                            outPoint
                    )) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new UtxoEntry(
                                    1_000L,
                                    scriptPubKey,
                                    100L,
                                    false
                            )
                    );
                };

        assertDoesNotThrow(
                () ->
                        InputScriptValidator.validate(
                                transaction,
                                0,
                                utxoView,
                                ScriptVerifyFlags.NONE
                        )
        );
    }
    private static byte[] csvScript(
            long requiredSequence
    ) {

        byte[] encoded =
                ScriptNumber.encode(
                        requiredSequence
                );

        byte[] script =
                new byte[
                        1
                                + encoded.length
                                + 3
                        ];

        int offset = 0;

        script[offset++] =
                (byte) encoded.length;

        System.arraycopy(
                encoded,
                0,
                script,
                offset,
                encoded.length
        );

        offset +=
                encoded.length;

        script[offset++] =
                (byte) Opcode.OP_CHECKSEQUENCEVERIFY;

        script[offset++] =
                (byte) Opcode.OP_DROP;

        script[offset] =
                (byte) Opcode.OP_1;

        return script;
    }
    @Test
    void csvUtxoMustValidateWhenSequenceRequirementIsSatisfied() {

        OutPoint outPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "55".repeat(32)
                        ),
                        new UInt32(0)
                );

        byte[] scriptPubKey =
                csvScript(
                        10L
                );

        Transaction transaction =
                new Transaction(
                        2,
                        List.of(
                                new TxIn(
                                        outPoint,
                                        new byte[0],
                                        new UInt32(
                                                15L
                                        )
                                )
                        ),
                        List.of(
                                new TxOut(
                                        900L,
                                        new byte[]{
                                                (byte) Opcode.OP_1
                                        }
                                )
                        ),
                        new UInt32(0)
                );

        UtxoView utxoView =
                requestedOutPoint -> {

                    if (!requestedOutPoint.equals(
                            outPoint
                    )) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new UtxoEntry(
                                    1_000L,
                                    scriptPubKey,
                                    100L,
                                    false
                            )
                    );
                };

        assertDoesNotThrow(
                () ->
                        InputScriptValidator.validate(
                                transaction,
                                0,
                                utxoView,
                                ScriptVerifyFlags
                                        .CHECKSEQUENCEVERIFY
                        )
        );
    }
    @Test
    void csvUtxoMustFailWhenInputSequenceIsTooSmall() {

        OutPoint outPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "66".repeat(32)
                        ),
                        new UInt32(0)
                );

        byte[] scriptPubKey =
                csvScript(
                        10L
                );

        Transaction transaction =
                new Transaction(
                        2,
                        List.of(
                                new TxIn(
                                        outPoint,
                                        new byte[0],
                                        new UInt32(
                                                9L
                                        )
                                )
                        ),
                        List.of(
                                new TxOut(
                                        900L,
                                        new byte[]{
                                                (byte) Opcode.OP_1
                                        }
                                )
                        ),
                        new UInt32(0)
                );

        UtxoView utxoView =
                requestedOutPoint -> {

                    if (!requestedOutPoint.equals(
                            outPoint
                    )) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new UtxoEntry(
                                    1_000L,
                                    scriptPubKey,
                                    100L,
                                    false
                            )
                    );
                };

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                transaction,
                                0,
                                utxoView,
                                ScriptVerifyFlags
                                        .CHECKSEQUENCEVERIFY
                        )
        );
    }
    @Test
    void csvOpcodeMustBehaveAsNopWhenConsensusFlagIsDisabled() {

        OutPoint outPoint =
                new OutPoint(
                        Hash256.fromDisplayHex(
                                "77".repeat(32)
                        ),
                        new UInt32(0)
                );

        byte[] scriptPubKey =
                csvScript(
                        100L
                );

        /*
         * version=1 и sequence final намеренно
         * не удовлетворяют CSV.
         *
         * Без flag opcode 0xb2 = OP_NOP3.
         */
        Transaction transaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        outPoint,
                                        new byte[0],
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        900L,
                                        new byte[]{
                                                (byte) Opcode.OP_1
                                        }
                                )
                        ),
                        new UInt32(0)
                );

        UtxoView utxoView =
                requestedOutPoint -> {

                    if (!requestedOutPoint.equals(
                            outPoint
                    )) {
                        return Optional.empty();
                    }

                    return Optional.of(
                            new UtxoEntry(
                                    1_000L,
                                    scriptPubKey,
                                    100L,
                                    false
                            )
                    );
                };

        assertDoesNotThrow(
                () ->
                        InputScriptValidator.validate(
                                transaction,
                                0,
                                utxoView,
                                ScriptVerifyFlags.NONE
                        )
        );
    }
}
