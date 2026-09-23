package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.time.Instant;
import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AddrManTest {

    private static final Instant NOW =
            Instant.parse(
                    "2026-09-23T00:00:00Z"
            );

    @Test
    void newAddressStartsInNewTable()
            throws Exception {

        PeerAddressManager manager =
                manager();

        PeerAddress address =
                address(
                        "192.0.2.1",
                        8333
                );

        KnownPeerAddress known =
                manager.add(
                        address,
                        NOW
                );

        assertTrue(
                known.isNew()
        );

        assertFalse(
                known.isTried()
        );

        assertEquals(
                1,
                manager.newSize()
        );

        assertEquals(
                0,
                manager.triedSize()
        );

        assertTrue(
                known.newBucketReferences() > 0
        );
    }

    @Test
    void successfulAddressMovesToTried()
            throws Exception {

        PeerAddressManager manager =
                manager();

        PeerAddress address =
                address(
                        "192.0.2.2",
                        8333
                );

        manager.add(
                address,
                NOW
        );

        manager.markSuccess(
                address,
                NOW.plusSeconds(
                        30
                )
        );

        KnownPeerAddress known =
                manager.find(
                        address
                ).orElseThrow();

        assertTrue(
                known.isTried()
        );

        assertFalse(
                known.isNew()
        );

        assertEquals(
                0,
                known.newBucketReferences()
        );

        assertEquals(
                0,
                known.attempts()
        );

        assertTrue(
                known.lastSuccess()
                        .isPresent()
        );
    }

    @Test
    void failedAttemptsDoNotPromoteAddress()
            throws Exception {

        PeerAddressManager manager =
                manager();

        PeerAddress address =
                address(
                        "192.0.2.3",
                        8333
                );

        manager.add(
                address,
                NOW
        );

        manager.markAttempt(
                address,
                NOW.plusSeconds(
                        10
                )
        );

        KnownPeerAddress known =
                manager.find(
                        address
                ).orElseThrow();

        assertTrue(
                known.isNew()
        );

        assertEquals(
                1,
                known.attempts()
        );
    }

    @Test
    void successResetsAttemptCounter()
            throws Exception {

        PeerAddressManager manager =
                manager();

        PeerAddress address =
                address(
                        "192.0.2.4",
                        8333
                );

        manager.add(
                address,
                NOW
        );

        manager.markAttempt(
                address,
                NOW.plusSeconds(
                        1
                )
        );

        manager.markAttempt(
                address,
                NOW.plusSeconds(
                        2
                )
        );

        manager.markSuccess(
                address,
                NOW.plusSeconds(
                        3
                )
        );

        assertEquals(
                0,
                manager.find(
                                address
                        ).orElseThrow()
                        .attempts()
        );
    }

    @Test
    void duplicateEndpointDoesNotCreateDuplicate()
            throws Exception {

        PeerAddressManager manager =
                manager();

        manager.add(
                new PeerAddress(
                        InetAddress.getByName(
                                "192.0.2.5"
                        ),
                        8333,
                        1L
                ),
                NOW
        );

        manager.add(
                new PeerAddress(
                        InetAddress.getByName(
                                "192.0.2.5"
                        ),
                        8333,
                        9L
                ),
                NOW.plusSeconds(
                        1
                )
        );

        assertEquals(
                1,
                manager.size()
        );

        assertEquals(
                9L,
                manager.addresses()
                        .get(0)
                        .peerAddress()
                        .services()
        );
    }

    @Test
    void getAddrReturnsAtMostTwentyThreePercent()
            throws Exception {

        PeerAddressManager manager =
                manager();

        for (int i = 1;
             i <= 100;
             i++) {

            manager.add(
                    address(
                            "10.0.0." + i,
                            8333
                    ),
                    NOW
            );
        }

        assertTrue(
                manager.getAddr()
                        .size()
                        <= 23
        );
    }

    @Test
    void getAddrNeverReturnsMoreThanOneThousand()
            throws Exception {

        PeerAddressManager manager =
                manager();

        for (int i = 0;
             i < 5000;
             i++) {

            int second =
                    (i / 254) % 254 + 1;

            int third =
                    i % 254 + 1;

            manager.add(
                    address(
                            "10."
                                    + second
                                    + "."
                                    + third
                                    + ".1",
                            8333
                    ),
                    NOW
            );
        }

        assertTrue(
                manager.getAddr()
                        .size()
                        <= 1000
        );
    }

    @Test
    void candidatesContainKnownAddresses()
            throws Exception {

        PeerAddressManager manager =
                manager();

        Set<PeerAddress> expected =
                new HashSet<>();

        for (int i = 1;
             i <= 20;
             i++) {

            PeerAddress address =
                    address(
                            "198.51.100."
                                    + i,
                            8333
                    );

            manager.add(
                    address,
                    NOW
            );

            expected.add(
                    address
            );
        }

        assertTrue(
                expected.containsAll(
                        manager.candidates()
                )
        );
    }

    @Test
    void constantsMatchAddrManStructure() {

        assertEquals(
                1024,
                PeerAddressManager.NEW_BUCKET_COUNT
        );

        assertEquals(
                256,
                PeerAddressManager.TRIED_BUCKET_COUNT
        );

        assertEquals(
                64,
                PeerAddressManager.BUCKET_SIZE
        );

        assertEquals(
                8,
                PeerAddressManager.MAX_NEW_REFERENCES
        );
    }

    private static PeerAddressManager manager() {

        byte[] key =
                new byte[32];

        for (int i = 0;
             i < key.length;
             i++) {

            key[i] =
                    (byte) (
                            i + 1
                    );
        }

        return new PeerAddressManager(
                key,
                new Random(
                        123456789L
                )
        );
    }

    private static PeerAddress address(
            String host,
            int port
    ) throws Exception {

        return new PeerAddress(
                InetAddress.getByName(
                        host
                ),
                port,
                1L
        );
    }
}