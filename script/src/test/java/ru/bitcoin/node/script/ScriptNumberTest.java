package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ScriptNumberTest {
    @Test
    void decodeShouldDecodePositiveNumber() {

        assertEquals(
                500L,
                ScriptNumber.decode(
                        ScriptNumber.encode(500L),
                        5
                )
        );
    }

    @Test
    void decodeShouldDecodeNegativeNumber() {

        assertEquals(
                -500L,
                ScriptNumber.decode(
                        ScriptNumber.encode(-500L),
                        5
                )
        );
    }

    @Test
    void decodeShouldDecodeNegativeZeroAsZero() {

        assertEquals(
                0L,
                ScriptNumber.decode(
                        new byte[]{
                                (byte) 0x80
                        },
                        5
                )
        );
    }

    @Test
    void decodeShouldAllowFiveByteLockTimeNumber() {

        byte[] encoded =
                ScriptNumber.encode(
                        0xffff_ffffL
                );

        assertEquals(
                5,
                encoded.length
        );

        assertEquals(
                0xffff_ffffL,
                ScriptNumber.decode(
                        encoded,
                        5
                )
        );
    }

    @Test
    void decodeShouldRejectNumberLargerThanMaximumSize() {

        assertThrows(
                ScriptExecutionException.class,
                () -> ScriptNumber.decode(
                        new byte[6],
                        5
                )
        );
    }
}
