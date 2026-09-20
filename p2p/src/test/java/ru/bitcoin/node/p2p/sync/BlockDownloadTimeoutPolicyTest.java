package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class BlockDownloadTimeoutPolicyTest {

    @Test
    void shouldUseTargetSpacingForSingleDownloadingPeer() {

        BlockDownloadTimeoutPolicy policy =
                bitcoinPolicy();

        assertEquals(
                Duration.ofMinutes(
                        10
                ),
                policy.timeout(
                        0
                )
        );
    }

    @Test
    void shouldIncreaseTimeoutByHalfTargetSpacingPerOtherPeer() {

        BlockDownloadTimeoutPolicy policy =
                bitcoinPolicy();

        assertEquals(
                Duration.ofMinutes(
                        15
                ),
                policy.timeout(
                        1
                )
        );

        assertEquals(
                Duration.ofMinutes(
                        20
                ),
                policy.timeout(
                        2
                )
        );

        assertEquals(
                Duration.ofMinutes(
                        25
                ),
                policy.timeout(
                        3
                )
        );

        assertEquals(
                Duration.ofMinutes(
                        30
                ),
                policy.timeout(
                        4
                )
        );
    }

    @Test
    void shouldNotTimeoutExactlyAtBoundary() {

        BlockDownloadTimeoutPolicy policy =
                bitcoinPolicy();

        assertFalse(
                policy.isTimedOut(
                        Duration.ofMinutes(
                                15
                        ),
                        1
                )
        );
    }

    @Test
    void shouldTimeoutAfterBoundary() {

        BlockDownloadTimeoutPolicy policy =
                bitcoinPolicy();

        assertTrue(
                policy.isTimedOut(
                        Duration.ofMinutes(
                                15
                        ).plusNanos(
                                1
                        ),
                        1
                )
        );
    }

    @Test
    void shouldNotTimeoutBeforeBoundary() {

        BlockDownloadTimeoutPolicy policy =
                bitcoinPolicy();

        assertFalse(
                policy.isTimedOut(
                        Duration.ofMinutes(
                                15
                        ).minusNanos(
                                1
                        ),
                        1
                )
        );
    }

    @Test
    void shouldSupportNonBitcoinTargetSpacing() {

        BlockDownloadTimeoutPolicy policy =
                new BlockDownloadTimeoutPolicy(
                        Duration.ofSeconds(
                                30
                        )
                );

        assertEquals(
                Duration.ofSeconds(
                        30
                ),
                policy.timeout(
                        0
                )
        );

        assertEquals(
                Duration.ofSeconds(
                        45
                ),
                policy.timeout(
                        1
                )
        );

        assertEquals(
                Duration.ofSeconds(
                        60
                ),
                policy.timeout(
                        2
                )
        );
    }

    @Test
    void shouldRejectInvalidArguments() {

        assertThrows(
                NullPointerException.class,
                () ->
                        new BlockDownloadTimeoutPolicy(
                                null
                        )
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockDownloadTimeoutPolicy(
                                Duration.ZERO
                        )
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new BlockDownloadTimeoutPolicy(
                                Duration.ofSeconds(
                                        -1
                                )
                        )
        );

        BlockDownloadTimeoutPolicy policy =
                bitcoinPolicy();

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.timeout(
                                -1
                        )
        );

        assertThrows(
                NullPointerException.class,
                () ->
                        policy.isTimedOut(
                                null,
                                0
                        )
        );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        policy.isTimedOut(
                                Duration.ofNanos(
                                        -1
                                ),
                                0
                        )
        );
    }

    private static BlockDownloadTimeoutPolicy bitcoinPolicy() {

        return new BlockDownloadTimeoutPolicy(
                Duration.ofMinutes(
                        10
                )
        );
    }
}