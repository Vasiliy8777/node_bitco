package ru.bitcoin.node.common.bytes;

public final class ByteUtils {

    private ByteUtils() {
    }

    public static byte[] reverse(byte[] bytes) {
        byte[] result = bytes.clone();

        for (int i = 0, j = result.length - 1; i < j; i++, j--) {
            byte temp = result[i];
            result[i] = result[j];
            result[j] = temp;
        }

        return result;
    }

    public static byte[] concat(byte[]... arrays) {
        int totalLength = 0;

        for (byte[] array : arrays) {
            totalLength += array.length;
        }

        byte[] result = new byte[totalLength];

        int offset = 0;

        for (byte[] array : arrays) {
            System.arraycopy(array, 0, result, offset, array.length);
            offset += array.length;
        }

        return result;
    }
}
