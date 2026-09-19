package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.common.bytes.LittleEndian;
import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.InventoryVector;
import ru.bitcoin.node.protocol.serialization.BitcoinReader;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

public final class InventoryVectorListCodec {

    public static final int MAX_INVENTORY_SIZE =
            50_000;

    private static final int INVENTORY_VECTOR_SIZE =
            36;

    private InventoryVectorListCodec() {
    }

    public static byte[] encode(
            List<InventoryVector> inventory
    ) {
        if (inventory == null) {
            throw new IllegalArgumentException(
                    "inventory must not be null"
            );
        }

        if (inventory.size() > MAX_INVENTORY_SIZE) {
            throw new IllegalArgumentException(
                    "Too many inventory entries"
            );
        }

        if (inventory.stream().anyMatch(
                item -> item == null
        )) {
            throw new IllegalArgumentException(
                    "inventory must not contain null"
            );
        }

        ByteArrayOutputStream out =
                new ByteArrayOutputStream();

        out.writeBytes(
                CompactSize.encode(
                        inventory.size()
                )
        );

        for (InventoryVector vector : inventory) {

            out.writeBytes(
                    LittleEndian.uint32(
                            vector.type()
                    )
            );

            out.writeBytes(
                    vector.hash().bytes()
            );
        }

        return out.toByteArray();
    }

    public static List<InventoryVector> decode(
            byte[] payload
    ) {
        if (payload == null) {
            throw new IllegalArgumentException(
                    "payload must not be null"
            );
        }

        BitcoinReader reader =
                new BitcoinReader(
                        payload
                );

        long rawCount =
                reader.readCompactSize();

        if (rawCount > MAX_INVENTORY_SIZE) {
            throw new IllegalArgumentException(
                    "Too many inventory entries"
            );
        }

        int count =
                reader.checkedCollectionSize(
                        rawCount,
                        INVENTORY_VECTOR_SIZE,
                        "inventory count"
                );

        List<InventoryVector> inventory =
                new ArrayList<>(
                        count
                );

        for (int i = 0; i < count; i++) {

            long type =
                    reader.readUInt32LE();

            Hash256 hash =
                    new Hash256(
                            reader.readBytes(
                                    Hash256.LENGTH
                            )
                    );

            inventory.add(
                    new InventoryVector(
                            type,
                            hash
                    )
            );
        }

        if (reader.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Unexpected bytes after inventory"
            );
        }

        return List.copyOf(
                inventory
        );
    }
}