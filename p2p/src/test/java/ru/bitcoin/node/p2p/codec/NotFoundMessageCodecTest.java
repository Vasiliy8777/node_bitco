package ru.bitcoin.node.p2p.codec;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.InventoryVector;
import ru.bitcoin.node.p2p.message.NotFoundMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NotFoundMessageCodecTest {

    @Test
    void roundTripsWitnessBlockInventory() {

        Hash256 hash =
                Hash256.fromDisplayHex(
                        "58fb5d854840e3d20f48f8226b56c2a6"
                                + "d6cba54e366a896de7d179fe70c34668"
                );

        NotFoundMessage original =
                new NotFoundMessage(
                        List.of(
                                new InventoryVector(
                                        InventoryVector.MSG_WITNESS_BLOCK,
                                        hash
                                )
                        )
                );

        byte[] encoded =
                NotFoundMessageCodec.encode(
                        original
                );

        NotFoundMessage decoded =
                NotFoundMessageCodec.decode(
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
    void usesInventoryVectorWireFormat() {

        Hash256 hash =
                new Hash256(
                        new byte[Hash256.LENGTH]
                );

        NotFoundMessage message =
                new NotFoundMessage(
                        List.of(
                                new InventoryVector(
                                        InventoryVector.MSG_WITNESS_BLOCK,
                                        hash
                                )
                        )
                );

        byte[] encoded =
                NotFoundMessageCodec.encode(
                        message
                );

        assertEquals(
                37,
                encoded.length
        );

        /*
         * CompactSize count = 1.
         */
        assertEquals(
                0x01,
                encoded[0] & 0xFF
        );

        /*
         * MSG_WITNESS_BLOCK = 0x40000002
         *
         * uint32 little-endian:
         *
         * 02 00 00 40
         */
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
    void rejectsTrailingBytes() {

        byte[] payload =
                new byte[38];

        payload[0] = 1;

        /*
         * MSG_BLOCK = 2.
         */
        payload[1] = 2;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        NotFoundMessageCodec.decode(
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
                () ->
                        NotFoundMessageCodec.decode(
                                payload
                        )
        );
    }

    @Test
    void rejectsNullInventory() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new NotFoundMessage(
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
                () ->
                        new NotFoundMessage(
                                inventory
                        )
        );
    }
}