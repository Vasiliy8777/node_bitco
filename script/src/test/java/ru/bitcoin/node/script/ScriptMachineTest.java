package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class ScriptMachineTest {
    @Test
    void copyShouldCreateIndependentStackSnapshot() {

        ScriptMachine original =
                new ScriptMachine();

        original.push(
                new byte[]{
                        0x01
                }
        );

        original.push(
                new byte[]{
                        0x02
                }
        );

        ScriptMachine copy =
                original.copy();

        assertEquals(
                2,
                copy.size()
        );

        assertArrayEquals(
                new byte[]{
                        0x02
                },
                copy.pop()
        );

        assertArrayEquals(
                new byte[]{
                        0x01
                },
                copy.pop()
        );

        /*
         * Original остался неизменным.
         */
        assertEquals(
                2,
                original.size()
        );

        assertArrayEquals(
                new byte[]{
                        0x02
                },
                original.peek()
        );
    }
}
