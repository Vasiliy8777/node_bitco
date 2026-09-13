package ru.bitcoin.node.script;

public final class ScriptNumber {

    private ScriptNumber() {
    }

    public static byte[] encode(long value) {

        if (value == 0) {
            return new byte[0];
        }

        boolean negative =
                value < 0;

        /*
         * Избегаем переполнения Math.abs(Long.MIN_VALUE).
         *
         * Текущие small-integer opcodes сюда передают только
         * значения -1..16, но метод оставляем безопасным.
         */
        if (value == Long.MIN_VALUE) {
            throw new IllegalArgumentException(
                    "Long.MIN_VALUE is not supported"
            );
        }

        long absolute =
                Math.abs(value);

        byte[] temporary =
                new byte[9];

        int size = 0;

        while (absolute != 0) {
            temporary[size++] =
                    (byte) (absolute & 0xff);

            absolute >>>= 8;
        }

        if ((temporary[size - 1] & 0x80) != 0) {

            temporary[size++] =
                    (byte) (
                            negative
                                    ? 0x80
                                    : 0x00
                    );

        } else if (negative) {

            temporary[size - 1] |=
                    (byte) 0x80;
        }

        byte[] result =
                new byte[size];

        System.arraycopy(
                temporary,
                0,
                result,
                0,
                size
        );

        return result;
    }

    public static boolean castToBool(
            byte[] value
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "value must not be null"
            );
        }

        for (int i = 0; i < value.length; i++) {

            if (value[i] != 0) {

                /*
                 * Bitcoin Script negative zero:
                 *
                 * 80
                 * 0080
                 * 000080
                 *
                 * Последний байт 0x80 при всех предыдущих
                 * нулевых байтах считается false.
                 */
                if (i == value.length - 1
                        && Byte.toUnsignedInt(
                        value[i]
                ) == 0x80) {

                    return false;
                }

                return true;
            }
        }

        return false;
    }
    public static long decode(
            byte[] value,
            int maxNumSize
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "value must not be null"
            );
        }

        if (maxNumSize < 0) {
            throw new IllegalArgumentException(
                    "maxNumSize must not be negative"
            );
        }

        if (value.length > maxNumSize) {
            throw new ScriptExecutionException(
                    "Script number exceeds maximum size of "
                            + maxNumSize
                            + " byte(s)"
            );
        }

        if (value.length == 0) {
            return 0L;
        }

        /*
         * Наши consensus call sites сейчас используют
         * максимум 5 bytes, поэтому значение гарантированно
         * помещается в signed long.
         */
        if (value.length > 8) {
            throw new ScriptExecutionException(
                    "Script number is too large for long"
            );
        }

        long result = 0L;

        for (int i = 0; i < value.length; i++) {

            result |=
                    ((long) Byte.toUnsignedInt(value[i]))
                            << (8 * i);
        }

        /*
         * Sign bit находится в старшем байте
         * Script Number.
         */
        int lastByte =
                Byte.toUnsignedInt(
                        value[value.length - 1]
                );

        if ((lastByte & 0x80) != 0) {

            long signBit =
                    0x80L
                            << (8 * (value.length - 1));

            return -(result & ~signBit);
        }

        return result;
    }
}