package ru.bitcoin.node.p2p.message;

import java.util.List;

public final class AddrV2Message {

    public static final int MAX_ADDRESSES = 1_000;

    private final List<AddrV2Entry> addresses;

    public AddrV2Message(
            List<AddrV2Entry> addresses
    ) {
        if (addresses == null) {
            throw new IllegalArgumentException(
                    "addresses must not be null"
            );
        }

        if (addresses.size() > MAX_ADDRESSES) {
            throw new IllegalArgumentException(
                    "Too many addresses"
            );
        }

        if (addresses.stream().anyMatch(
                address -> address == null
        )) {
            throw new IllegalArgumentException(
                    "addresses must not contain null"
            );
        }

        this.addresses =
                List.copyOf(addresses);
    }

    public List<AddrV2Entry> addresses() {
        return addresses;
    }

    public int size() {
        return addresses.size();
    }
}