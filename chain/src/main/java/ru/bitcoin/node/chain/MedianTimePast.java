package ru.bitcoin.node.chain;

import java.util.Arrays;

public final class MedianTimePast {

    private static final int MEDIAN_TIME_SPAN = 11;

    private MedianTimePast() {
    }

    public static long calculate(
            BlockIndex tip,
            BlockIndexLookup lookup
    ) {
        if (tip == null) {
            throw new IllegalArgumentException(
                    "tip must not be null"
            );
        }

        if (lookup == null) {
            throw new IllegalArgumentException(
                    "lookup must not be null"
            );
        }

        long[] timestamps =
                new long[MEDIAN_TIME_SPAN];

        int count = 0;

        BlockIndex current = tip;

        while (current != null
                && count < MEDIAN_TIME_SPAN) {

            timestamps[count++] =
                    current.header()
                            .timestamp()
                            .value();

            /*
             * Дальше идти уже не нужно:
             *
             * либо дошли до genesis,
             * либо уже собрали необходимые 11 блоков.
             */
            if (current.height() == 0
                    || count == MEDIAN_TIME_SPAN) {
                break;
            }

            BlockIndex parent =
                    lookup.find(
                            current.previousBlockHash()
                    );

            if (parent == null) {
                throw new IllegalStateException(
                        "Missing ancestor while calculating "
                                + "MedianTimePast: "
                                + current.previousBlockHash()
                                .toDisplayHex()
                );
            }

            current = parent;
        }

        long[] values =
                Arrays.copyOf(
                        timestamps,
                        count
                );

        Arrays.sort(values);

        /*
         * Bitcoin Core:
         * median of up to the last 11 blocks.
         *
         * При нечётном количестве это центральный элемент.
         * При чётном количестве используется верхний
         * центральный элемент: values[count / 2].
         */
        return values[count / 2];
    }
}