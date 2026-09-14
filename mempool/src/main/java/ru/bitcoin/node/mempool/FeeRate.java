package ru.bitcoin.node.mempool;

import java.util.Objects;

/**
 * Fee rate represented as satoshis per 1000 virtual bytes.
 *
 * Аналог концепции Bitcoin Core CFeeRate.
 */
public final class FeeRate
        implements Comparable<FeeRate> {

    public static final long BYTES_PER_KILOBYTE =
            1000L;

    private final long satoshisPerKiloByte;

    /**
     * Создаёт fee rate в sat/kvB.
     */
    public FeeRate(
            long satoshisPerKiloByte
    ) {
        if (satoshisPerKiloByte < 0) {
            throw new IllegalArgumentException(
                    "satoshisPerKiloByte must not be negative"
            );
        }

        this.satoshisPerKiloByte =
                satoshisPerKiloByte;
    }

    /**
     * Создаёт fee rate из фактически уплаченной комиссии
     * и virtual transaction size.
     *
     * Результат хранится как sat/kvB.
     *
     * Здесь rate округляется вниз:
     *
     * fee * 1000 / vsize
     *
     * Такое представление удобно для сравнений.
     */
    public static FeeRate fromFeeAndVSize(
            long fee,
            long virtualSize
    ) {
        if (fee < 0) {
            throw new IllegalArgumentException(
                    "fee must not be negative"
            );
        }

        if (virtualSize <= 0) {
            throw new IllegalArgumentException(
                    "virtualSize must be positive"
            );
        }

        long perKiloByte =
                Math.multiplyExact(
                        fee,
                        BYTES_PER_KILOBYTE
                )
                        / virtualSize;

        return new FeeRate(
                perKiloByte
        );
    }

    public static FeeRate fromFeeAndWeight(
            long fee,
            long weight
    ) {
        long virtualSize =
                virtualSizeFromWeight(
                        weight
                );

        return fromFeeAndVSize(
                fee,
                virtualSize
        );
    }

    /**
     * Минимальная комиссия для указанного vsize.
     *
     * Округление вверх обязательно:
     *
     * ceil(rate * vsize / 1000)
     */
    public long feeForVSize(
            long virtualSize
    ) {
        if (virtualSize < 0) {
            throw new IllegalArgumentException(
                    "virtualSize must not be negative"
            );
        }

        if (virtualSize == 0
                || satoshisPerKiloByte == 0) {

            return 0L;
        }

        long product =
                Math.multiplyExact(
                        satoshisPerKiloByte,
                        virtualSize
                );

        return Math.addExact(
                product,
                BYTES_PER_KILOBYTE - 1
        )
                / BYTES_PER_KILOBYTE;
    }

    public long feeForWeight(
            long weight
    ) {
        return feeForVSize(
                virtualSizeFromWeight(
                        weight
                )
        );
    }

    public long satoshisPerKiloByte() {
        return satoshisPerKiloByte;
    }

    public double satoshisPerVByte() {
        return (double) satoshisPerKiloByte
                / BYTES_PER_KILOBYTE;
    }

    private static long virtualSizeFromWeight(
            long weight
    ) {
        if (weight < 0) {
            throw new IllegalArgumentException(
                    "weight must not be negative"
            );
        }

        /*
         * ceil(weight / 4)
         */
        return Math.addExact(
                weight,
                3L
        )
                / 4L;
    }

    @Override
    public int compareTo(
            FeeRate other
    ) {
        Objects.requireNonNull(
                other,
                "other"
        );

        return Long.compare(
                satoshisPerKiloByte,
                other.satoshisPerKiloByte
        );
    }

    @Override
    public boolean equals(
            Object object
    ) {
        if (this == object) {
            return true;
        }

        if (!(object instanceof FeeRate other)) {
            return false;
        }

        return satoshisPerKiloByte
                == other.satoshisPerKiloByte;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(
                satoshisPerKiloByte
        );
    }

    @Override
    public String toString() {
        return satoshisPerKiloByte
                + " sat/kvB";
    }
}