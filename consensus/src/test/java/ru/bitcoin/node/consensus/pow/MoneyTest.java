package ru.bitcoin.node.consensus.pow;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.consensus.money.Money;

import static org.junit.jupiter.api.Assertions.*;

class MoneyTest {

    @Test
    void shouldDefineBitcoinMaximumMoney() {

        assertEquals(
                2_100_000_000_000_000L,
                Money.MAX_MONEY
        );
    }

    @Test
    void shouldValidateAmounts() {

        assertTrue(
                Money.isValidAmount(0)
        );

        assertTrue(
                Money.isValidAmount(
                        Money.MAX_MONEY
                )
        );

        assertFalse(
                Money.isValidAmount(-1)
        );

        assertFalse(
                Money.isValidAmount(
                        Money.MAX_MONEY + 1
                )
        );
    }
}
