package ru.bitcoin.node.p2p.address;

import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

public final class AddrManTestFixture {

    private AddrManTestFixture() {
    }

    public static PeerAddressManager deterministicManager() {

        byte[] secretKey =
                new byte[32];

        for (int i = 0;
             i < secretKey.length;
             i++) {

            secretKey[i] =
                    (byte) (
                            i + 1
                    );
        }

        return new PeerAddressManager(
                secretKey,
                new Random(
                        1L
                )
        );
    }

    public static PeerAddress[] findTriedCollision(
            PeerAddressManager manager
    ) throws Exception {

        Map<Long, PeerAddress> positions =
                new HashMap<>();

        for (int i = 1;
             i <= 65_535;
             i++) {

            int third =
                    i >>> 8;

            int fourth =
                    i & 0xff;

            PeerAddress address =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "198.51."
                                            + third
                                            + "."
                                            + fourth
                            ),
                            8333,
                            0L
                    );

            int bucket =
                    manager.triedBucketForTesting(
                            address
                    );

            int slot =
                    manager.triedSlotForTesting(
                            address
                    );

            long position =
                    (
                            (long) bucket
                                    << 32
                    )
                            | (
                            slot
                                    & 0xffffffffL
                    );

            PeerAddress existing =
                    positions.putIfAbsent(
                            position,
                            address
                    );

            if (existing != null
                    && !existing.equals(
                    address
            )) {

                return new PeerAddress[]{
                        existing,
                        address
                };
            }
        }

        throw new AssertionError(
                "Unable to find deterministic TRIED collision"
        );
    }

    public static PeerAddress[] findTriedCollision(
            PeerAddressManager manager,
            int port
    ) throws Exception {

        Map<Long, PeerAddress> positions =
                new HashMap<>();

        for (int i = 1;
             i <= 65_535;
             i++) {

            int third =
                    i >>> 8;

            int fourth =
                    i & 0xff;

            PeerAddress address =
                    new PeerAddress(
                            InetAddress.getByName(
                                    "127."
                                            + (third & 0xff)
                                            + "."
                                            + (fourth & 0xff)
                                            + ".1"
                            ),
                            port,
                            0L
                    );

            int bucket =
                    manager.triedBucketForTesting(
                            address
                    );

            int slot =
                    manager.triedSlotForTesting(
                            address
                    );

            long position =
                    ((long) bucket << 32)
                            | (slot & 0xffffffffL);

            PeerAddress existing =
                    positions.putIfAbsent(
                            position,
                            address
                    );

            if (existing != null
                    && !existing.equals(address)) {

                return new PeerAddress[]{
                        existing,
                        address
                };
            }
        }

        throw new AssertionError(
                "Unable to find deterministic TRIED collision"
        );
    }
}