package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.protocol.transaction.Witness;
import ru.bitcoin.node.script.Opcode;
import ru.bitcoin.node.script.ScriptExecutionException;
import ru.bitcoin.node.script.ScriptVerifyFlags;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InputScriptValidatorUpgradableWitnessTest {

    @Test
    void nativeV1MustPassConsensusWithoutDiscourageFlag() {

        Fixture fixture =
                nativeWitnessFixture(
                        1,
                        32,
                        new byte[0]
                );

        assertDoesNotThrow(
                () ->
                        InputScriptValidator.validate(
                                fixture.transaction(),
                                0,
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
                        )
        );
    }

    @Test
    void nativeV1MustFailPolicyWithDiscourageFlag() {

        Fixture fixture =
                nativeWitnessFixture(
                        1,
                        32,
                        new byte[0]
                );

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        InputScriptValidator.validate(
                                fixture.transaction(),
                                0,
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
                                        | ScriptVerifyFlags
                                        .DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM
                        )
        );
    }

    @Test
    void nativeV16MustPassConsensusWithoutDiscourageFlag() {

        Fixture fixture =
                nativeWitnessFixture(
                        16,
                        40,
                        new byte[0]
                );

        assertDoesNotThrow(
                () ->
                        InputScriptValidator.validate(
                                fixture.transaction(),
                                0,
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
                        )
        );
    }

    @Test
    void nativeV16MustFailPolicyWithDiscourageFlag() {

        Fixture fixture =
                nativeWitnessFixture(
                        16,
                        40,
                        new byte[0]
                );

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        InputScriptValidator.validate(
                                fixture.transaction(),
                                0,
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
                                        | ScriptVerifyFlags
                                        .DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM
                        )
        );
    }

    @Test
    void nativeUnknownWitnessMustRequireEmptyScriptSig() {

        Fixture fixture =
                nativeWitnessFixture(
                        1,
                        32,
                        new byte[]{
                                (byte) Opcode.OP_1
                        }
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                fixture.transaction(),
                                0,
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
                        )
        );
    }

    @Test
    void wrappedV1MustPassConsensusWithoutDiscourageFlag() {

        Fixture fixture =
                wrappedWitnessFixture(
                        1,
                        32
                );

        assertDoesNotThrow(
                () ->
                        InputScriptValidator.validate(
                                fixture.transaction(),
                                0,
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
                                        | ScriptVerifyFlags.P2SH
                        )
        );
    }

    @Test
    void wrappedV1MustFailPolicyWithDiscourageFlag() {

        Fixture fixture =
                wrappedWitnessFixture(
                        1,
                        32
                );

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        InputScriptValidator.validate(
                                fixture.transaction(),
                                0,
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
                                        | ScriptVerifyFlags.P2SH
                                        | ScriptVerifyFlags
                                        .DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM
                        )
        );
    }

    @Test
    void witnessV0MustNotBeRejectedByDiscourageFlagItself() {

        /*
         * Проверяем через реальный v0 P2WPKH-shaped
         * witness program.
         *
         * Сам spend здесь невалиден, потому что
         * witness stack пуст.
         *
         * Главное: failure должен идти из v0
         * verification, а не из
         * DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM.
         */
        Fixture fixture =
                nativeWitnessFixture(
                        0,
                        20,
                        new byte[0]
                );

        assertThrows(
                TransactionValidationException.class,
                () ->
                        InputScriptValidator.validate(
                                fixture.transaction(),
                                0,
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
                                        | ScriptVerifyFlags
                                        .DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM
                        )
        );
    }

    private static Fixture nativeWitnessFixture(
            int version,
            int programLength,
            byte[] scriptSig
    ) {

        byte[] witnessProgram =
                witnessProgram(
                        version,
                        programLength
                );

        OutPoint previousOutput =
                previousOutput(
                        (byte) 0x61
                );

        TxIn input =
                new TxIn(
                        previousOutput,
                        scriptSig,
                        new UInt32(
                                0xffff_fffeL
                        ),
                        Witness.EMPTY
                );

        Transaction transaction =
                transaction(
                        input
                );

        UtxoView view =
                utxoView(
                        previousOutput,
                        witnessProgram
                );

        return new Fixture(
                transaction,
                view
        );
    }

    private static Fixture wrappedWitnessFixture(
            int version,
            int programLength
    ) {

        /*
         * redeemScript сам является witness program:
         *
         * OP_1 PUSH32 <program>
         *
         * либо другой version/length,
         * переданный в helper.
         */
        byte[] redeemScript =
                witnessProgram(
                        version,
                        programLength
                );

        byte[] redeemScriptHash =
                Hash160.hash(
                        redeemScript
                );

        /*
         * P2SH:
         *
         * OP_HASH160
         * PUSH20
         * HASH160(redeemScript)
         * OP_EQUAL
         */
        ByteArrayOutputStream scriptPubKey =
                new ByteArrayOutputStream();

        scriptPubKey.write(
                Opcode.OP_HASH160
        );

        scriptPubKey.write(
                20
        );

        scriptPubKey.writeBytes(
                redeemScriptHash
        );

        scriptPubKey.write(
                Opcode.OP_EQUAL
        );

        /*
         * Nested witness требует scriptSig,
         * состоящий ровно из одного push
         * witness redeemScript.
         *
         * Witness program имеет максимум
         * 42 bytes, поэтому direct push достаточен.
         */
        ByteArrayOutputStream scriptSig =
                new ByteArrayOutputStream();

        scriptSig.write(
                redeemScript.length
        );

        scriptSig.writeBytes(
                redeemScript
        );

        OutPoint previousOutput =
                previousOutput(
                        (byte) 0x62
                );

        TxIn input =
                new TxIn(
                        previousOutput,
                        scriptSig.toByteArray(),
                        new UInt32(
                                0xffff_fffeL
                        ),
                        Witness.EMPTY
                );

        Transaction transaction =
                transaction(
                        input
                );

        UtxoView view =
                utxoView(
                        previousOutput,
                        scriptPubKey.toByteArray()
                );

        return new Fixture(
                transaction,
                view
        );
    }

    private static byte[] witnessProgram(
            int version,
            int programLength
    ) {

        if (version < 0
                || version > 16) {

            throw new IllegalArgumentException(
                    "Witness version must be 0..16"
            );
        }

        if (programLength < 2
                || programLength > 40) {

            throw new IllegalArgumentException(
                    "Witness program length must be 2..40"
            );
        }

        byte[] script =
                new byte[
                        2 + programLength
                        ];

        if (version == 0) {

            script[0] =
                    (byte) Opcode.OP_0;

        } else {

            script[0] =
                    (byte) (
                            Opcode.OP_1
                                    + version
                                    - 1
                    );
        }

        /*
         * Witness program всегда использует
         * direct push.
         */
        script[1] =
                (byte) programLength;

        /*
         * Используем ненулевые deterministic bytes,
         * чтобы fixture было легче диагностировать.
         */
        Arrays.fill(
                script,
                2,
                script.length,
                (byte) 0x42
        );

        return script;
    }

    private static OutPoint previousOutput(
            byte fill
    ) {

        byte[] previousHash =
                new byte[32];

        Arrays.fill(
                previousHash,
                fill
        );

        return new OutPoint(
                new Hash256(
                        previousHash
                ),
                new UInt32(0L)
        );
    }

    private static Transaction transaction(
            TxIn input
    ) {

        TxOut output =
                new TxOut(
                        90_000L,
                        new byte[]{
                                (byte) Opcode.OP_1
                        }
                );

        return new Transaction(
                2,
                List.of(
                        input
                ),
                List.of(
                        output
                ),
                new UInt32(0L)
        );
    }

    private static UtxoView utxoView(
            OutPoint previousOutput,
            byte[] scriptPubKey
    ) {

        UtxoEntry utxo =
                new UtxoEntry(
                        100_000L,
                        scriptPubKey,
                        100,
                        false
                );

        return outPoint -> {

            if (previousOutput.equals(
                    outPoint
            )) {

                return Optional.of(
                        utxo
                );
            }

            return Optional.empty();
        };
    }

    private record Fixture(
            Transaction transaction,
            UtxoView utxoView
    ) {
    }
}