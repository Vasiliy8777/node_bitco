package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.InvMessage;
import ru.bitcoin.node.p2p.message.InventoryVector;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InvMessageCodecTest {

    @Test
    void roundTripsInventory() {

        Hash256 txHash =
                Hash256.fromDisplayHex(
                        "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
                );

        Hash256 blockHash =
                Hash256.fromDisplayHex(
                        "58fb5d854840e3d20f48f8226b56c2a6d6cba54e366a896de7d179fe70c34668"
                );

        InvMessage original =
                new InvMessage(
                        List.of(
                                new InventoryVector(
                                        InventoryVector.MSG_TX,
                                        txHash
                                ),
                                new InventoryVector(
                                        InventoryVector.MSG_BLOCK,
                                        blockHash
                                )
                        )
                );

        byte[] encoded =
                InvMessageCodec.encode(
                        original
                );

        InvMessage decoded =
                InvMessageCodec.decode(
                        encoded
                );

        assertEquals(
                2,
                decoded.size()
        );

        assertEquals(
                InventoryVector.MSG_TX,
                decoded.inventory()
                        .get(0)
                        .type()
        );

        assertEquals(
                txHash,
                decoded.inventory()
                        .get(0)
                        .hash()
        );

        assertEquals(
                InventoryVector.MSG_BLOCK,
                decoded.inventory()
                        .get(1)
                        .type()
        );

        assertEquals(
                blockHash,
                decoded.inventory()
                        .get(1)
                        .hash()
        );
    }

    @Test
    void encodesInventoryVectorInBitcoinWireFormat() {

        Hash256 hash =
                new Hash256(
                        new byte[32]
                );

        InvMessage message =
                new InvMessage(
                        List.of(
                                new InventoryVector(
                                        InventoryVector.MSG_BLOCK,
                                        hash
                                )
                        )
                );

        byte[] encoded =
                InvMessageCodec.encode(
                        message
                );

        assertEquals(
                37,
                encoded.length
        );

        // CompactSize count = 1
        assertEquals(
                0x01,
                encoded[0] & 0xFF
        );

        // MSG_BLOCK = 2 serialized as uint32 LE:
        // 02 00 00 00
        assertEquals(
                0x02,
                encoded[1] & 0xFF
        );
        assertEquals(
                0x00,
                encoded[2] & 0xFF
        );
        assertEquals(
                0x00,
                encoded[3] & 0xFF
        );
        assertEquals(
                0x00,
                encoded[4] & 0xFF
        );
    }

    @Test
    void supportsWitnessInventoryType() {

        Hash256 hash =
                new Hash256(
                        new byte[32]
                );

        InvMessage message =
                new InvMessage(
                        List.of(
                                new InventoryVector(
                                        InventoryVector.MSG_WITNESS_BLOCK,
                                        hash
                                )
                        )
                );

        byte[] encoded =
                InvMessageCodec.encode(
                        message
                );

        // MSG_WITNESS_BLOCK = 0x40000002
        // uint32 little-endian = 02 00 00 40

        assertEquals(
                0x02,
                encoded[1] & 0xFF
        );
        assertEquals(
                0x00,
                encoded[2] & 0xFF
        );
        assertEquals(
                0x00,
                encoded[3] & 0xFF
        );
        assertEquals(
                0x40,
                encoded[4] & 0xFF
        );
    }

    @Test
    void supportsEmptyInventory() {

        InvMessage message =
                new InvMessage(
                        List.of()
                );

        byte[] encoded =
                InvMessageCodec.encode(
                        message
                );

        assertArrayEquals(
                new byte[]{0x00},
                encoded
        );

        InvMessage decoded =
                InvMessageCodec.decode(
                        encoded
                );

        assertEquals(
                0,
                decoded.size()
        );
    }

    @Test
    void rejectsNullInventory() {

        assertThrows(
                IllegalArgumentException.class,
                () -> new InvMessage(
                        null
                )
        );
    }

    @Test
    void rejectsNullInventoryEntry() {

        List<InventoryVector> inventory =
                new java.util.ArrayList<>();

        inventory.add(
                null
        );

        assertThrows(
                IllegalArgumentException.class,
                () -> new InvMessage(
                        inventory
                )
        );
    }

    @Test
    void rejectsMoreThanMaximumInventoryEntries() {

        InventoryVector vector =
                new InventoryVector(
                        InventoryVector.MSG_BLOCK,
                        new Hash256(
                                new byte[32]
                        )
                );

        List<InventoryVector> inventory =
                java.util.Collections.nCopies(
                        InvMessage.MAX_INVENTORY_SIZE + 1,
                        vector
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> new InvMessage(
                        inventory
                )
        );
    }

    @Test
    void rejectsTrailingBytes() {

        byte[] payload =
                new byte[38];

        payload[0] = 1;

        // MSG_BLOCK
        payload[1] = 2;

        assertThrows(
                IllegalArgumentException.class,
                () -> InvMessageCodec.decode(
                        payload
                )
        );
    }

    @Test
    void rejectsTruncatedInventoryVector() {

        byte[] payload =
                new byte[36];

        payload[0] = 1;

        assertThrows(
                IllegalArgumentException.class,
                () -> InvMessageCodec.decode(
                        payload
                )
        );
    }
}