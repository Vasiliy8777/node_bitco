package ru.bitcoin.node.consensus.pow;

import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.network.NetworkParameters;

public final class NextWorkRequired {

    private NextWorkRequired() {
    }
    public static UInt32 calculate(
            long nextHeight,
            UInt32 previousBits,
            UInt32 firstBlockBits,
            long firstBlockTimestamp,
            long previousBlockTimestamp,
            NetworkParameters parameters
    ) {
        if (previousBits == null) {
            throw new IllegalArgumentException(
                    "previousBits must not be null"
            );
        }

        if (firstBlockBits == null) {
            throw new IllegalArgumentException(
                    "firstBlockBits must not be null"
            );
        }

        if (parameters == null) {
            throw new IllegalArgumentException(
                    "parameters must not be null"
            );
        }

        if (nextHeight < 0) {
            throw new IllegalArgumentException(
                    "nextHeight must not be negative"
            );
        }

        if (parameters.noRetargeting()) {
            return previousBits;
        }

        int interval =
                parameters
                        .difficultyAdjustmentInterval();

        if (nextHeight % interval != 0) {
            return previousBits;
        }

        long actualTimespan =
                previousBlockTimestamp
                        - firstBlockTimestamp;

        /*
         * BIP94:
         *
         * На Testnet4 при retarget базовая difficulty
         * берётся из первого блока периода.
         *
         * На остальных сетях — из последнего.
         */
        UInt32 baseBits =
                parameters.enforceBip94()
                        ? firstBlockBits
                        : previousBits;

        long nextBits =
                DifficultyAdjustment.calculateNextBits(
                        baseBits.value(),
                        actualTimespan,
                        parameters
                );

        return new UInt32(
                nextBits
        );
    }
    public static UInt32 calculate(
            long nextHeight,
            UInt32 previousBits,
            long firstBlockTimestamp,
            long previousBlockTimestamp,
            NetworkParameters parameters
    ) {
        if (previousBits == null) {
            throw new IllegalArgumentException(
                    "previousBits must not be null"
            );
        }

        if (parameters == null) {
            throw new IllegalArgumentException(
                    "parameters must not be null"
            );
        }

        if (nextHeight < 0) {
            throw new IllegalArgumentException(
                    "nextHeight must not be negative"
            );
        }

        /*
         * Для regtest пересчёт сложности отключён.
         */
        if (parameters.noRetargeting()) {
            return previousBits;
        }

        int interval =
                parameters.difficultyAdjustmentInterval();

        /*
         * Если это не граница периода difficulty adjustment,
         * обычные сети сохраняют предыдущий bits.
         *
         * Правило min-difficulty для testnet добавим отдельно,
         * потому что оно требует timestamp нового блока
         * и истории предыдущих блоков.
         */
        if (nextHeight % interval != 0) {
            return previousBits;
        }

        long actualTimespan =
                previousBlockTimestamp
                        - firstBlockTimestamp;

        long nextBits =
                DifficultyAdjustment.calculateNextBits(
                        previousBits.value(),
                        actualTimespan,
                        parameters
                );

        return new UInt32(nextBits);
    }
}