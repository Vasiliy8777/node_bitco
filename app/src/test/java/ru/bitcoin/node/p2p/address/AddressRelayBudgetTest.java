package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AddressRelayBudgetTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void startsWithOneTokenForImmediateSelfAnnouncement() {
        var budget = new AddressRelayBudget(0L);

        assertEquals(
                1,
                budget.takeAddresses(
                        1000,
                        0L
                )
        );

        assertEquals(
                0,
                budget.takeAddresses(
                        1,
                        0L
                )
        );
    }

    @Test
    void refillsAtCoreRateOfPointOneAddressPerSecond() {
        var budget = new AddressRelayBudget(0L);

        assertEquals(
                1,
                budget.takeAddresses(
                        1,
                        0L
                )
        );

        assertEquals(
                0,
                budget.takeAddresses(
                        1,
                        9L * SECOND
                )
        );

        assertEquals(
                1,
                budget.takeAddresses(
                        1,
                        10L * SECOND
                )
        );

        assertEquals(
                0,
                budget.takeAddresses(
                        1,
                        19L * SECOND
                )
        );

        assertEquals(
                1,
                budget.takeAddresses(
                        1,
                        20L * SECOND
                )
        );
    }

    @Test
    void normalRefillIsCappedAtOneThousandAddresses() {
        var budget = new AddressRelayBudget(0L);

        assertEquals(
                1000.0d,
                budget.available(
                        20_000L * SECOND
                ),
                0.0000001d
        );

        assertEquals(
                1000,
                budget.takeAddresses(
                        2000,
                        20_000L * SECOND
                )
        );

        assertEquals(
                0,
                budget.takeAddresses(
                        1,
                        20_000L * SECOND
                )
        );
    }

    @Test
    void backwardsMonotonicTimeDoesNotMintTokens() {
        var budget = new AddressRelayBudget(100L * SECOND);

        assertEquals(
                1,
                budget.takeAddresses(
                        1,
                        100L * SECOND
                )
        );

        assertEquals(
                0,
                budget.takeAddresses(
                        1,
                        90L * SECOND
                )
        );
    }

    @Test
    void addrAndAddrV2CanShareTheSameRecordBudgetWithoutMessageQuota() {
        var budget = new AddressRelayBudget(0L);

        assertEquals(
                1,
                budget.takeAddresses(
                        500,
                        0L
                )
        );

        for (int i = 0; i < 50; i++) {
            assertEquals(
                    0,
                    budget.takeAddresses(
                            1,
                            0L
                    )
            );
        }

        assertEquals(
                1,
                budget.takeAddresses(
                        1,
                        10L * SECOND
                )
        );
    }
}
