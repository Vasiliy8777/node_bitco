package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.crypto.hash.Ripemd160;
import ru.bitcoin.node.crypto.hash.Sha1;
import ru.bitcoin.node.crypto.hash.Sha256;

import static org.junit.jupiter.api.Assertions.*;

class ScriptHashOpcodeTest {

    @Test
    void ripemd160MustHashTopStackElement() {

        byte[] value = {
                0x01,
                0x02,
                0x03
        };

        ScriptMachine machine =
                executeHashOpcode(
                        value,
                        Opcode.OP_RIPEMD160
                );

        assertEquals(
                1,
                machine.size()
        );

        assertArrayEquals(
                Ripemd160.hash(value),
                machine.peek()
        );

        assertEquals(
                20,
                machine.peek().length
        );
    }

    @Test
    void sha1MustHashTopStackElement() {

        byte[] value = {
                0x01,
                0x02,
                0x03
        };

        ScriptMachine machine =
                executeHashOpcode(
                        value,
                        Opcode.OP_SHA1
                );

        assertEquals(
                1,
                machine.size()
        );

        assertArrayEquals(
                Sha1.hash(value),
                machine.peek()
        );

        assertEquals(
                20,
                machine.peek().length
        );
    }

    @Test
    void sha256MustHashTopStackElement() {

        byte[] value = {
                0x01,
                0x02,
                0x03
        };

        ScriptMachine machine =
                executeHashOpcode(
                        value,
                        Opcode.OP_SHA256
                );

        assertEquals(
                1,
                machine.size()
        );

        assertArrayEquals(
                Sha256.hash(value),
                machine.peek()
        );

        assertEquals(
                32,
                machine.peek().length
        );
    }

    @Test
    void hash160MustHashTopStackElement() {

        byte[] value = {
                0x01,
                0x02,
                0x03
        };

        ScriptMachine machine =
                executeHashOpcode(
                        value,
                        Opcode.OP_HASH160
                );

        assertEquals(
                1,
                machine.size()
        );

        assertArrayEquals(
                Hash160.hash(value),
                machine.peek()
        );

        assertEquals(
                20,
                machine.peek().length
        );
    }

    @Test
    void hash256MustDoubleSha256TopStackElement() {

        byte[] value = {
                0x01,
                0x02,
                0x03
        };

        ScriptMachine machine =
                executeHashOpcode(
                        value,
                        Opcode.OP_HASH256
                );

        assertEquals(
                1,
                machine.size()
        );

        assertArrayEquals(
                Hash256Digest.hashBytes(value),
                machine.peek()
        );

        assertEquals(
                32,
                machine.peek().length
        );
    }

    @Test
    void hashOpcodeMustConsumeOriginalElement() {

        ScriptMachine machine =
                new ScriptMachine();

        machine.push(
                new byte[]{
                        0x11
                }
        );

        machine.push(
                new byte[]{
                        0x22,
                        0x33
                }
        );

        ScriptInterpreter.execute(
                new byte[]{
                        (byte) Opcode.OP_SHA256
                },
                machine
        );

        /*
         * Первый элемент сохраняется,
         * второй заменяется своим SHA256.
         */
        assertEquals(
                2,
                machine.size()
        );

        assertArrayEquals(
                Sha256.hash(
                        new byte[]{
                                0x22,
                                0x33
                        }
                ),
                machine.peek()
        );

        assertArrayEquals(
                new byte[]{
                        0x11
                },
                machine.peekFromTop(1)
        );
    }

    @Test
    void hashOpcodeMustWorkWithEmptyByteArray() {

        ScriptMachine machine =
                new ScriptMachine();

        /*
         * OP_0 pushes empty byte array.
         */
        byte[] script = {
                (byte) Opcode.OP_0,
                (byte) Opcode.OP_SHA256
        };

        ScriptInterpreter.execute(
                script,
                machine
        );

        assertArrayEquals(
                Sha256.hash(
                        new byte[0]
                ),
                machine.peek()
        );
    }

    @Test
    void hashOpcodeMustFailOnEmptyStack() {

        byte[] script = {
                (byte) Opcode.OP_HASH256
        };

        assertThrows(
                ScriptExecutionException.class,
                () ->
                        ScriptInterpreter.execute(
                                script,
                                new ScriptMachine()
                        )
        );
    }

    private static ScriptMachine executeHashOpcode(
            byte[] value,
            int opcode
    ) {
        ScriptMachine machine =
                new ScriptMachine();

        /*
         * Здесь кладём данные непосредственно
         * в machine, чтобы тестировать сам opcode,
         * а не Script push serialization.
         */
        machine.push(
                value
        );

        ScriptInterpreter.execute(
                new byte[]{
                        (byte) opcode
                },
                machine
        );

        return machine;
    }
}