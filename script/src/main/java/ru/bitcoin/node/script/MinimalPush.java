package ru.bitcoin.node.script;

public final class MinimalPush {

    private MinimalPush() {
    }

    /**
     * Проверяет, является ли push минимально закодированным
     * по правилам Bitcoin Script.
     *
     * Метод предполагает, что opcode действительно является
     * push-opcode, а data содержит фактически помещаемые в стек байты.
     */
    public static boolean isMinimal(
            int opcode,
            byte[] data
    ) {
        if (data == null) {
            throw new IllegalArgumentException(
                    "data must not be null"
            );
        }

        int size =
                data.length;

        /*
         * Пустой byte vector должен кодироваться OP_0.
         */
        if (size == 0) {
            return opcode
                    == Opcode.OP_0;
        }

        /*
         * Числа 1..16 должны использовать
         *
         * OP_1 .. OP_16
         *
         * вместо raw push одного байта.
         */
        if (size == 1
                && (data[0] & 0xff) >= 1
                && (data[0] & 0xff) <= 16) {

            int expectedOpcode =
                    Opcode.OP_1
                            + (data[0] & 0xff)
                            - 1;

            return opcode
                    == expectedOpcode;
        }

        /*
         * -1 имеет специальное минимальное
         * представление OP_1NEGATE.
         *
         * ScriptNum(-1) = 0x81.
         */
        if (size == 1
                && (data[0] & 0xff) == 0x81) {

            return opcode
                    == Opcode.OP_1NEGATE;
        }

        /*
         * До 75 bytes:
         *
         * сам opcode должен быть длиной данных.
         *
         * Например:
         *
         * 0x14 <20 bytes>
         *
         * а не:
         *
         * OP_PUSHDATA1 0x14 <20 bytes>
         */
        if (size <= Opcode.OP_DATA_MAX) {

            return opcode
                    == size;
        }

        /*
         * 76..255 bytes:
         *
         * минимальный вариант OP_PUSHDATA1.
         */
        if (size <= 0xff) {

            return opcode
                    == Opcode.OP_PUSHDATA1;
        }

        /*
         * 256..65535 bytes:
         *
         * минимальный вариант OP_PUSHDATA2.
         */
        if (size <= 0xffff) {

            return opcode
                    == Opcode.OP_PUSHDATA2;
        }

        /*
         * Для большего значения минимальным становится
         * OP_PUSHDATA4.
         *
         * В обычном Bitcoin Script это позже всё равно
         * ограничивается MAX_SCRIPT_SIZE = 10_000,
         * но сама функция минимальности остаётся общей.
         */
        return opcode
                == Opcode.OP_PUSHDATA4;
    }
}