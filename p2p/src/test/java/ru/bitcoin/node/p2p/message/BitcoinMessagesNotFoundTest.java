package ru.bitcoin.node.p2p.message;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BitcoinMessagesNotFoundTest {

    @Test
    void createsAndDecodesNotFoundMessage() {

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

        BitcoinMessage message =
                BitcoinMessages.notFound(
                        original
                );

        assertEquals(
                "notfound",
                message.command()
        );

        NotFoundMessage decoded =
                BitcoinMessages.decodeNotFound(
                        message
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
    void rejectsWrongCommandWhenDecodingNotFound() {

        BitcoinMessage message =
                new BitcoinMessage(
                        "getdata",
                        new byte[]{0}
                );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BitcoinMessages.decodeNotFound(
                                message
                        )
        );
    }

    @Test
    void rejectsNullNotFoundMessage() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        BitcoinMessages.notFound(
                                null
                        )
        );
    }
}