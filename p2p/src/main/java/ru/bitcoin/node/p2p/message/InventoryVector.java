package ru.bitcoin.node.p2p.message;

import ru.bitcoin.node.common.types.Hash256;

import java.util.Objects;

public record InventoryVector(
        long type,
        Hash256 hash
) {

    public static final long MSG_TX = 1L;
    public static final long MSG_BLOCK = 2L;
    public static final long MSG_FILTERED_BLOCK = 3L;
    public static final long MSG_CMPCT_BLOCK = 4L;

    public static final long MSG_WITNESS_FLAG = 1L << 30;

    public static final long MSG_WITNESS_TX =
            MSG_TX | MSG_WITNESS_FLAG;

    public static final long MSG_WITNESS_BLOCK =
            MSG_BLOCK | MSG_WITNESS_FLAG;

    public InventoryVector {
        if (type < 0 || type > 0xFFFF_FFFFL) {
            throw new IllegalArgumentException(
                    "inventory type must fit uint32"
            );
        }

        Objects.requireNonNull(
                hash,
                "hash"
        );
    }
}