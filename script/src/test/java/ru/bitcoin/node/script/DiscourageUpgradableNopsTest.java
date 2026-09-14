package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DiscourageUpgradableNopsTest {

    @Test
    void ordinaryNopMustRemainAllowedWithDiscourageFlag() {

        byte[] script = {
                (byte) Opcode.OP_NOP
        };

        assertDoesNotThrow(
                () -> execute(
                        script,
                        ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_NOPS
                )
        );
    }

    @Test
    void nop1MustBeRejectedWithDiscourageFlag() {

        assertDiscouraged(
                Opcode.OP_NOP1
        );
    }

    @Test
    void nop4MustBeRejectedWithDiscourageFlag() {

        assertDiscouraged(
                Opcode.OP_NOP4
        );
    }

    @Test
    void nop5MustBeRejectedWithDiscourageFlag() {

        assertDiscouraged(
                Opcode.OP_NOP5
        );
    }

    @Test
    void nop6MustBeRejectedWithDiscourageFlag() {

        assertDiscouraged(
                Opcode.OP_NOP6
        );
    }

    @Test
    void nop7MustBeRejectedWithDiscourageFlag() {

        assertDiscouraged(
                Opcode.OP_NOP7
        );
    }

    @Test
    void nop8MustBeRejectedWithDiscourageFlag() {

        assertDiscouraged(
                Opcode.OP_NOP8
        );
    }

    @Test
    void nop9MustBeRejectedWithDiscourageFlag() {

        assertDiscouraged(
                Opcode.OP_NOP9
        );
    }

    @Test
    void nop10MustBeRejectedWithDiscourageFlag() {

        assertDiscouraged(
                Opcode.OP_NOP10
        );
    }

    @Test
    void upgradableNopWithoutDiscourageFlagMustRemainAllowed() {

        byte[] script = {
                (byte) Opcode.OP_NOP4
        };

        assertDoesNotThrow(
                () -> execute(
                        script,
                        ScriptVerifyFlags.NONE
                )
        );
    }

    @Test
    void upgradableNopInsideInactiveBranchMustNotFail() {

        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_IF,

                (byte) Opcode.OP_NOP4,

                (byte) Opcode.OP_ENDIF
        };

        assertDoesNotThrow(
                () -> execute(
                        script,
                        ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_NOPS
                )
        );
    }

    private static void assertDiscouraged(
            int opcode
    ) {

        byte[] script = {
                (byte) opcode
        };

        assertThrows(
                ScriptExecutionException.class,
                () -> execute(
                        script,
                        ScriptVerifyFlags.DISCOURAGE_UPGRADABLE_NOPS
                )
        );
    }

    private static void execute(
            byte[] script,
            int flags
    ) {

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * Для NOP policy нам не требуется transaction,
         * но context нужен, чтобы передать flags.
         *
         * Если текущий ScriptExecutionContext
         * не допускает null transaction, используй
         * тот же transaction fixture, что в
         * ScriptMinimalIfTest/ScriptNullFailTest.
         */
        ScriptExecutionContext context =
                new ScriptExecutionContext(
                        transaction(),
                        0,
                        script,
                        flags
                );

        ScriptInterpreter.execute(
                script,
                machine,
                context
        );
    }
    private static Transaction transaction() {

        byte[] previousHash =
                new byte[32];

        /*
         * Не используем полностью zero hash,
         * чтобы fixture точно не выглядел
         * как coinbase prevout.
         */
        previousHash[0] =
                0x44;

        return new Transaction(
                2,
                List.of(
                        new TxIn(
                                new OutPoint(
                                        new Hash256(
                                                previousHash
                                        ),
                                        new UInt32(0)
                                ),
                                new byte[0],
                                new UInt32(
                                        0xffff_fffeL
                                )
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
}