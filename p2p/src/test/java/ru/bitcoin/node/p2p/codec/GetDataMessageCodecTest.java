package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.GetDataMessage;
import ru.bitcoin.node.p2p.message.InventoryVector;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GetDataMessageCodecTest {

    @Test
    void roundTripsWitnessBlockInventory() {

        Hash256 hash =
                Hash256.fromDisplayHex(
                        "58fb5d854840e3d20f48f8226b56c2a6d6cba54e366a896de7d179fe70c34668"
                );

        GetDataMessage original =
                new GetDataMessage(
                        List.of(
                                new InventoryVector(
                                        InventoryVector.MSG_WITNESS_BLOCK,
                                        hash
                                )
                        )
                );

        byte[] encoded =
                GetDataMessageCodec.encode(
                        original
                );

        GetDataMessage decoded =
                GetDataMessageCodec.decode(
                        encoded
                );

        assertEquals(
                1,
                decoded.size()
        );

        assertEquals(
                InventoryVector.MSG_WITNESS_BLOCK,
                decoded.inventory()
                        .get(0)
                        .type()
        );

        assertEquals(
                hash,
                decoded.inventory()
                        .get(0)
                        .hash()
        );
    }

    @Test
    void encodesWitnessBlockTypeAsUint32LittleEndian() {

        Hash256 hash =
                new Hash256(
                        new byte[32]
                );

        GetDataMessage message =
                new GetDataMessage(
                        List.of(
                                new InventoryVector(
                                        InventoryVector.MSG_WITNESS_BLOCK,
                                        hash
                                )
                        )
                );

        byte[] encoded =
                GetDataMessageCodec.encode(
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

        // 0x40000002 serialized little-endian:
        // 02 00 00 40
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
                        GetDataMessage.MAX_INVENTORY_SIZE + 1,
                        vector
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> new GetDataMessage(
                        inventory
                )
        );
    }

    @Test
    void rejectsTrailingBytes() {

        byte[] payload =
                new byte[38];

        payload[0] = 1;

        // type MSG_BLOCK = 2
        payload[1] = 2;

        assertThrows(
                IllegalArgumentException.class,
                () -> GetDataMessageCodec.decode(
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
                () -> GetDataMessageCodec.decode(
                        payload
                )
        );
    }
}