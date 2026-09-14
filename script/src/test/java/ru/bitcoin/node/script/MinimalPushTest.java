package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MinimalPushTest {

    @Test
    void emptyVectorMustUseOpZero() {

        assertTrue(
                MinimalPush.isMinimal(
                        Opcode.OP_0,
                        new byte[0]
                )
        );

        assertFalse(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA1,
                        new byte[0]
                )
        );
    }

    @Test
    void numberOneMustUseOpOne() {

        byte[] data = {
                0x01
        };

        assertTrue(
                MinimalPush.isMinimal(
                        Opcode.OP_1,
                        data
                )
        );

        /*
         * 0x01 0x01 было бы функционально допустимо,
         * но не минимально при MINIMALDATA.
         */
        assertFalse(
                MinimalPush.isMinimal(
                        0x01,
                        data
                )
        );

        assertFalse(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA1,
                        data
                )
        );
    }

    @Test
    void numberSixteenMustUseOpSixteen() {

        byte[] data = {
                0x10
        };

        assertTrue(
                MinimalPush.isMinimal(
                        Opcode.OP_16,
                        data
                )
        );

        assertFalse(
                MinimalPush.isMinimal(
                        0x01,
                        data
                )
        );
    }

    @Test
    void minusOneMustUseOpOneNegate() {

        byte[] data = {
                (byte) 0x81
        };

        assertTrue(
                MinimalPush.isMinimal(
                        Opcode.OP_1NEGATE,
                        data
                )
        );

        assertFalse(
                MinimalPush.isMinimal(
                        0x01,
                        data
                )
        );
    }

    @Test
    void ordinarySingleByteMustUseDirectPush() {

        byte[] data = {
                0x11
        };

        /*
         * 0x11 не является small integer opcode value.
         *
         * Значит минимальный вариант:
         *
         * 01 11
         */
        assertTrue(
                MinimalPush.isMinimal(
                        0x01,
                        data
                )
        );

        assertFalse(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA1,
                        data
                )
        );
    }

    @Test
    void seventyFiveBytesMustUseDirectPush() {

        byte[] data =
                new byte[75];

        assertTrue(
                MinimalPush.isMinimal(
                        75,
                        data
                )
        );

        assertFalse(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA1,
                        data
                )
        );
    }

    @Test
    void seventySixBytesMustUsePushData1() {

        byte[] data =
                new byte[76];

        assertTrue(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA1,
                        data
                )
        );

        assertFalse(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA2,
                        data
                )
        );
    }

    @Test
    void twoHundredFiftyFiveBytesMustUsePushData1() {

        byte[] data =
                new byte[255];

        assertTrue(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA1,
                        data
                )
        );

        assertFalse(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA2,
                        data
                )
        );
    }

    @Test
    void twoHundredFiftySixBytesMustUsePushData2() {

        byte[] data =
                new byte[256];

        assertTrue(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA2,
                        data
                )
        );

        assertFalse(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA1,
                        data
                )
        );
    }

    @Test
    void sixtyFiveThousandFiveHundredThirtyFiveBytesMustUsePushData2() {

        byte[] data =
                new byte[65_535];

        assertTrue(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA2,
                        data
                )
        );

        assertFalse(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA4,
                        data
                )
        );
    }

    @Test
    void sixtyFiveThousandFiveHundredThirtySixBytesMustUsePushData4() {

        byte[] data =
                new byte[65_536];

        assertTrue(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA4,
                        data
                )
        );

        assertFalse(
                MinimalPush.isMinimal(
                        Opcode.OP_PUSHDATA2,
                        data
                )
        );
    }

    @Test
    void nullDataMustFail() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        MinimalPush.isMinimal(
                                Opcode.OP_0,
                                null
                        )
        );
    }
}