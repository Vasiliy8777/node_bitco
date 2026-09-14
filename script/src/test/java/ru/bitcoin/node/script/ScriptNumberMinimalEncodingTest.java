package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ScriptNumberMinimalEncodingTest {

    @Test
    void emptyVectorMustBeMinimalZero() {

        assertTrue(
                ScriptNumber.isMinimallyEncoded(
                        new byte[0]
                )
        );
    }

    @Test
    void singleZeroByteMustNotBeMinimal() {

        assertFalse(
                ScriptNumber.isMinimallyEncoded(
                        new byte[]{
                                0x00
                        }
                )
        );
    }

    @Test
    void negativeZeroMustNotBeMinimal() {

        assertFalse(
                ScriptNumber.isMinimallyEncoded(
                        new byte[]{
                                (byte) 0x80
                        }
                )
        );
    }

    @Test
    void oneMustBeMinimal() {

        assertTrue(
                ScriptNumber.isMinimallyEncoded(
                        new byte[]{
                                0x01
                        }
                )
        );
    }

    @Test
    void redundantZeroByteMustNotBeMinimal() {

        /*
         * 01 00 == +1,
         * но минимально +1 = 01.
         */
        assertFalse(
                ScriptNumber.isMinimallyEncoded(
                        new byte[]{
                                0x01,
                                0x00
                        }
                )
        );
    }

    @Test
    void redundantNegativeSignByteMustNotBeMinimal() {

        /*
         * 01 80 == -1,
         * но минимально -1 = 81.
         */
        assertFalse(
                ScriptNumber.isMinimallyEncoded(
                        new byte[]{
                                0x01,
                                (byte) 0x80
                        }
                )
        );
    }

    @Test
    void positive128NeedsExtraZeroByte() {

        /*
         * 80 имеет sign bit.
         *
         * Поэтому +128:
         *
         * 80 00
         */
        assertTrue(
                ScriptNumber.isMinimallyEncoded(
                        new byte[]{
                                (byte) 0x80,
                                0x00
                        }
                )
        );

        assertEquals(
                128,
                ScriptNumber.decode(
                        new byte[]{
                                (byte) 0x80,
                                0x00
                        },
                        4,
                        true
                )
        );
    }

    @Test
    void negative128MustBeMinimal() {

        assertTrue(
                ScriptNumber.isMinimallyEncoded(
                        new byte[]{
                                (byte) 0x80,
                                (byte) 0x80
                        }
                )
        );

        assertEquals(
                -128,
                ScriptNumber.decode(
                        new byte[]{
                                (byte) 0x80,
                                (byte) 0x80
                        },
                        4,
                        true
                )
        );
    }

    @Test
    void requireMinimalMustRejectRedundantEncoding() {

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptNumber.decode(
                                new byte[]{
                                        0x01,
                                        0x00
                                },
                                4,
                                true
                        )
        );
    }

    @Test
    void withoutRequireMinimalSameValueMustRemainAccepted() {

        assertEquals(
                1,
                ScriptNumber.decode(
                        new byte[]{
                                0x01,
                                0x00
                        },
                        4,
                        false
                )
        );
    }
}