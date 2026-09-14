package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.transaction.InputScriptValidator;
import ru.bitcoin.node.consensus.transaction.UtxoEntry;
import ru.bitcoin.node.consensus.transaction.UtxoView;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.protocol.transaction.Witness;
import ru.bitcoin.node.script.Opcode;
import ru.bitcoin.node.script.ScriptExecutionException;
import ru.bitcoin.node.script.ScriptVerifyFlags;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MempoolValidatorScriptPolicyTest {

    private static final long UTXO_AMOUNT =
            100_000L;

    private static final long SPENDING_HEIGHT =
            200L;

    @Test
    void unknownWitnessVersionMustBeConsensusValidButNonStandard() {

        Fixture fixture =
                createNativeV1Fixture();

        /*
         * Наш текущий pre-Taproot consensus path:
         *
         * BIP141 unknown witness version
         * является forward-compatible.
         *
         * Поэтому только WITNESS не должен
         * отклонять spend.
         */
        assertDoesNotThrow(
                () ->
                        InputScriptValidator.validateAll(
                                fixture.transaction(),
                                fixture.utxoView(),
                                ScriptVerifyFlags.WITNESS
                        )
        );

        /*
         * Тот же самый transaction через
         * mempool admission использует
         * STANDARD policy.
         *
         * STANDARD содержит:
         *
         * DISCOURAGE_UPGRADABLE_WITNESS_PROGRAM
         *
         * Поэтому transaction должна быть
         * отвергнута mempool policy.
         */
        assertThrows(
                ScriptExecutionException.class,
                () ->
                        MempoolValidator.validate(
                                fixture.transaction(),
                                SPENDING_HEIGHT,
                                fixture.utxoView()
                        )
        );
    }

    private static Fixture createNativeV1Fixture() {

        /*
         * Witness v1 program:
         *
         * OP_1
         * PUSH32
         * <32 bytes>
         *
         * В нашей текущей реализации до Taproot
         * version 1 пока проходит через
         * unknown/upgradable witness path.
         */
        byte[] scriptPubKey =
                new byte[34];

        scriptPubKey[0] =
                (byte) Opcode.OP_1;

        scriptPubKey[1] =
                32;

        Arrays.fill(
                scriptPubKey,
                2,
                scriptPubKey.length,
                (byte) 0x42
        );

        byte[] previousHash =
                new byte[32];

        Arrays.fill(
                previousHash,
                (byte) 0x71
        );

        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(
                                previousHash
                        ),
                        new UInt32(0L)
                );

        TxIn input =
                new TxIn(
                        previousOutput,
                        new byte[0],
                        new UInt32(
                                0xffff_fffeL
                        ),
                        Witness.EMPTY
                );

        /*
         * Output меньше input:
         *
         * input  = 100000 sat
         * output =  90000 sat
         * fee    =  10000 sat
         *
         * Поэтому contextual amount validation
         * сама по себе должна пройти.
         */
        TxOut output =
                new TxOut(
                        90_000L,
                        new byte[]{
                                (byte) Opcode.OP_1
                        }
                );

        Transaction transaction =
                new Transaction(
                        2,
                        List.of(
                                input
                        ),
                        List.of(
                                output
                        ),
                        new UInt32(0L)
                );

        UtxoEntry utxo =
                new UtxoEntry(
                        UTXO_AMOUNT,
                        scriptPubKey,
                        100L,
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
                transaction,
                view
        );
    }

    private record Fixture(
            Transaction transaction,
            UtxoView utxoView
    ) {
    }
}