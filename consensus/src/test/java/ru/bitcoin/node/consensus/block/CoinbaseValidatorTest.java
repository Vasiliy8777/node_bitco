package ru.bitcoin.node.consensus.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.money.Money;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoinbaseValidatorTest {

    private static final NetworkParameters MAINNET =
            NetworkParametersRegistry.mainnet();

    @Test
    void shouldAcceptExactSubsidyAndFees() {

        Transaction coinbase =
                coinbase(
                        50L * Money.SATOSHIS_PER_BTC
                                + 1_000L
                );

        assertDoesNotThrow(
                () -> CoinbaseValidator.validateReward(
                        coinbase,
                        0,
                        1_000L,
                        MAINNET
                )
        );
    }

    @Test
    void shouldAcceptRewardBelowMaximum() {

        Transaction coinbase =
                coinbase(
                        49L * Money.SATOSHIS_PER_BTC
                );

        assertDoesNotThrow(
                () -> CoinbaseValidator.validateReward(
                        coinbase,
                        0,
                        1_000L,
                        MAINNET
                )
        );
    }

    @Test
    void shouldRejectOneSatoshiAboveMaximum() {

        Transaction coinbase =
                coinbase(
                        50L * Money.SATOSHIS_PER_BTC
                                + 1_001L
                );

        assertThrows(
                BlockValidationException.class,
                () -> CoinbaseValidator.validateReward(
                        coinbase,
                        0,
                        1_000L,
                        MAINNET
                )
        );
    }

    @Test
    void shouldUseSubsidyAtCurrentHeight() {

        Transaction coinbase =
                coinbase(
                        25L * Money.SATOSHIS_PER_BTC
                );

        assertDoesNotThrow(
                () -> CoinbaseValidator.validateReward(
                        coinbase,
                        210_000L,
                        0L,
                        MAINNET
                )
        );
    }

    @Test
    void shouldRejectPreviousSubsidyAfterHalving() {

        Transaction coinbase =
                coinbase(
                        50L * Money.SATOSHIS_PER_BTC
                );

        assertThrows(
                BlockValidationException.class,
                () -> CoinbaseValidator.validateReward(
                        coinbase,
                        210_000L,
                        0L,
                        MAINNET
                )
        );
    }

    private static Transaction coinbase(
            long value
    ) {
        return new Transaction(
                1,
                List.of(
                        new TxIn(
                                OutPoint.coinbase(),
                                new byte[]{
                                        0x01,
                                        0x01
                                },
                                TxIn.FINAL_SEQUENCE
                        )
                ),
                List.of(
                        new TxOut(
                                value,
                                new byte[]{0x51}
                        )
                ),
                new UInt32(0)
        );
    }
}