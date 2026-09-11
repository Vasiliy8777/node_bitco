package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoredUtxoSerializerTest {

    @Test
    void shouldSerializeAndDeserializeRegularUtxo() {

        StoredUtxo original =
                new StoredUtxo(
                        123_456_789L,
                        new byte[]{
                                0x00,
                                0x14,
                                0x11,
                                0x22,
                                0x33,
                                0x44
                        },
                        850_000L,
                        false
                );

        byte[] serialized =
                StoredUtxoSerializer.serialize(
                        original
                );

        StoredUtxo restored =
                StoredUtxoSerializer.deserialize(
                        serialized
                );

        assertEquals(
                original,
                restored
        );
    }

    @Test
    void shouldSerializeAndDeserializeCoinbaseUtxo() {

        StoredUtxo original =
                new StoredUtxo(
                        5_000_000_000L,
                        new byte[]{
                                0x51
                        },
                        0L,
                        true
                );

        byte[] serialized =
                StoredUtxoSerializer.serialize(
                        original
                );

        StoredUtxo restored =
                StoredUtxoSerializer.deserialize(
                        serialized
                );

        assertEquals(
                original,
                restored
        );

        assertTrue(
                restored.coinbase()
        );
    }

    @Test
    void shouldSupportEmptyScriptPubKey() {

        StoredUtxo original =
                new StoredUtxo(
                        1000L,
                        new byte[0],
                        100L,
                        false
                );

        byte[] serialized =
                StoredUtxoSerializer.serialize(
                        original
                );

        StoredUtxo restored =
                StoredUtxoSerializer.deserialize(
                        serialized
                );

        assertEquals(
                original,
                restored
        );

        assertEquals(
                0,
                restored.scriptPubKey().length
        );
    }

    @Test
    void shouldPreserveMaximumMoneyValue() {

        StoredUtxo original =
                new StoredUtxo(
                        StoredUtxo.MAX_MONEY,
                        new byte[]{0x51},
                        900_000L,
                        false
                );

        StoredUtxo restored =
                StoredUtxoSerializer.deserialize(
                        StoredUtxoSerializer.serialize(
                                original
                        )
                );

        assertEquals(
                StoredUtxo.MAX_MONEY,
                restored.amount()
        );
    }

    @Test
    void shouldPreserveLargeHeight() {

        StoredUtxo original =
                new StoredUtxo(
                        1000L,
                        new byte[]{0x51},
                        4_000_000_000L,
                        false
                );

        StoredUtxo restored =
                StoredUtxoSerializer.deserialize(
                        StoredUtxoSerializer.serialize(
                                original
                        )
                );

        assertEquals(
                4_000_000_000L,
                restored.height()
        );
    }

    @Test
    void shouldRejectUnknownFormatVersion() {

        StoredUtxo utxo =
                new StoredUtxo(
                        1000L,
                        new byte[]{0x51},
                        1L,
                        false
                );

        byte[] serialized =
                StoredUtxoSerializer.serialize(
                        utxo
                );

        serialized[0] = 2;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        StoredUtxoSerializer.deserialize(
                                serialized
                        )
        );
    }

    @Test
    void shouldRejectInvalidCoinbaseFlag() {

        StoredUtxo utxo =
                new StoredUtxo(
                        1000L,
                        new byte[]{0x51},
                        1L,
                        false
                );

        byte[] serialized =
                StoredUtxoSerializer.serialize(
                        utxo
                );

        /*
         * Layout:
         *
         * 0      version
         * 1..8   amount
         * 9..16  height
         * 17     coinbase
         */
        serialized[17] = 2;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        StoredUtxoSerializer.deserialize(
                                serialized
                        )
        );
    }

    @Test
    void shouldRejectInvalidScriptLength() {

        StoredUtxo utxo =
                new StoredUtxo(
                        1000L,
                        new byte[]{
                                0x51,
                                0x52
                        },
                        1L,
                        false
                );

        byte[] serialized =
                StoredUtxoSerializer.serialize(
                        utxo
                );

        /*
         * scriptLength начинается с offset 18.
         *
         * Реально script = 2 байта.
         * Подменяем длину на 3.
         */
        serialized[18] = 3;
        serialized[19] = 0;
        serialized[20] = 0;
        serialized[21] = 0;

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        StoredUtxoSerializer.deserialize(
                                serialized
                        )
        );
    }

    @Test
    void shouldRejectTruncatedData() {

        byte[] invalid = {
                1,
                0,
                0
        };

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        StoredUtxoSerializer.deserialize(
                                invalid
                        )
        );
    }
}