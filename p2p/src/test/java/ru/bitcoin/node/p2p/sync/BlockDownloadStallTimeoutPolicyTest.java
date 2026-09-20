package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BlockDownloadStallTimeoutPolicyTest {

    @Test
    void shouldUseBitcoinCoreDefaultTimeout() {

        BlockDownloadStallTimeoutPolicy policy =
                new BlockDownloadStallTimeoutPolicy();

        assertEquals(
                Duration.ofSeconds(2),
                policy.timeout()
        );
    }

    @Test
    void shouldNotTimeoutAtExactBoundary() {

        BlockDownloadStallTimeoutPolicy policy =
                new BlockDownloadStallTimeoutPolicy();

        assertFalse(
                policy.isTimedOut(
                        Duration.ofSeconds(2)
                )
        );
    }

    @Test
    void shouldTimeoutAfterBoundary() {

        BlockDownloadStallTimeoutPolicy policy =
                new BlockDownloadStallTimeoutPolicy();

        assertTrue(
                policy.isTimedOut(
                        Duration.ofSeconds(2)
                                .plusNanos(1)
                )
        );
    }

    @Test
    void shouldNotTimeoutBeforeBoundary() {

        BlockDownloadStallTimeoutPolicy policy =
                new BlockDownloadStallTimeoutPolicy();

        assertFalse(
                policy.isTimedOut(
                        Duration.ofSeconds(2)
                                .minusNanos(1)
                )
        );
    }

    @Test
    void shouldDoubleTimeoutAfterStallDisconnect() {

        BlockDownloadStallTimeoutPolicy policy =
                new BlockDownloadStallTimeoutPolicy();

        policy.increaseAfterTimeout();

        assertEquals(
                Duration.ofSeconds(4),
                policy.timeout()
        );

        policy.increaseAfterTimeout();

        assertEquals(
                Duration.ofSeconds(8),
                policy.timeout()
        );

        policy.increaseAfterTimeout();

        assertEquals(
                Duration.ofSeconds(16),
                policy.timeout()
        );

        policy.increaseAfterTimeout();

        assertEquals(
                Duration.ofSeconds(32),
                policy.timeout()
        );

        policy.increaseAfterTimeout();

        assertEquals(
                Duration.ofSeconds(64),
                policy.timeout()
        );
    }

    @Test
    void shouldNeverExceedMaximumTimeout() {

        BlockDownloadStallTimeoutPolicy policy =
                new BlockDownloadStallTimeoutPolicy();

        for (int i = 0;
             i < 10;
             i++) {

            policy.increaseAfterTimeout();
        }

        assertEquals(
                Duration.ofSeconds(64),
                policy.timeout()
        );

        assertEquals(
                BlockDownloadStallTimeoutPolicy.MAX_TIMEOUT,
                policy.timeout()
        );
    }

    @Test
    void shouldSupportCustomTimeoutForDeterministicTests() {

        BlockDownloadStallTimeoutPolicy policy =
                new BlockDownloadStallTimeoutPolicy(
                        Duration.ofMillis(50)
                );

        assertEquals(
                Duration.ofMillis(50),
                policy.timeout()
        );

        assertFalse(
                policy.isTimedOut(
                        Duration.ofMillis(50)
                )
        );

        assertTrue(
                policy.isTimedOut(
                        Duration.ofMillis(50)
                                .plusNanos(1)
                )
        );
    }

    @Test
    void shouldRejectInvalidInitialTimeout() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockDownloadStallTimeoutPolicy(
                                Duration.ZERO
                        )
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockDownloadStallTimeoutPolicy(
                                Duration.ofSeconds(-1)
                        )
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockDownloadStallTimeoutPolicy(
                                Duration.ofSeconds(65)
                        )
        );
    }

    @Test
    void shouldRejectInvalidStallingAge() {

        BlockDownloadStallTimeoutPolicy policy =
                new BlockDownloadStallTimeoutPolicy();

        assertThrows(
                NullPointerException.class,
                () ->
                        policy.isTimedOut(
                                null
                        )
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.isTimedOut(
                                Duration.ofNanos(-1)
                        )
        );
    }
}