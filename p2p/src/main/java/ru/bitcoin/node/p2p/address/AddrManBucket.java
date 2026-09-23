package ru.bitcoin.node.p2p.address;

import java.util.ArrayList;
import java.util.List;

final class AddrManBucket {

    static final int CAPACITY =
            64;

    private final PeerAddressKey[] slots =
            new PeerAddressKey[
                    CAPACITY
                    ];

    PeerAddressKey get(
            int slot
    ) {
        validateSlot(
                slot
        );

        return slots[slot];
    }

    void put(
            int slot,
            PeerAddressKey key
    ) {
        validateSlot(
                slot
        );

        slots[slot] =
                key;
    }

    void remove(
            int slot
    ) {
        validateSlot(
                slot
        );

        slots[slot] =
                null;
    }

    void remove(
            PeerAddressKey key
    ) {

        for (int i = 0;
             i < slots.length;
             i++) {

            if (key.equals(
                    slots[i]
            )) {

                slots[i] =
                        null;
            }
        }
    }

    boolean contains(
            PeerAddressKey key
    ) {

        for (PeerAddressKey slot :
                slots) {

            if (key.equals(
                    slot
            )) {
                return true;
            }
        }

        return false;
    }

    List<PeerAddressKey> entries() {

        List<PeerAddressKey> result =
                new ArrayList<>();

        for (PeerAddressKey slot :
                slots) {

            if (slot != null) {
                result.add(
                        slot
                );
            }
        }

        return List.copyOf(
                result
        );
    }

    private static void validateSlot(
            int slot
    ) {

        if (slot < 0
                || slot >= CAPACITY) {

            throw new IllegalArgumentException(
                    "Invalid AddrMan slot: "
                            + slot
            );
        }
    }
}