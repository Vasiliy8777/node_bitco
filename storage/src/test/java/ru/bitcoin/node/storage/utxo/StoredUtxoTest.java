package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class StoredUtxoTest {

    @Test
    void shouldCreateRegularUtxo() {

        byte[] scriptPubKey = {
                0x00,
                0x14,
                0x01,
                0x02,
                0x03
        };

        StoredUtxo utxo =
                new StoredUtxo(
                        50_000L,
                        scriptPubKey,
                        100L,
                        false
                );

        assertEquals(
                50_000L,
                utxo.amount()
        );

        assertArrayEquals(
                scriptPubKey,
                utxo.scriptPubKey()
        );

        assertEquals(
                100L,
                utxo.height()
        );

        assertFalse(
                utxo.coinbase()
        );
    }

    @Test
    void shouldCreateCoinbaseUtxo() {

        StoredUtxo utxo =
                new StoredUtxo(
                        5_000_000_000L,
                        new byte[]{0x51},
                        0L,
                        true
                );

        assertTrue(
                utxo.coinbase()
        );

        assertEquals(
                0L,
                utxo.height()
        );
    }

    @Test
    void shouldRejectNegativeAmount() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StoredUtxo(
                                -1L,
                                new byte[]{0x51},
                                1L,
                                false
                        )
        );
    }

    @Test
    void shouldRejectAmountAboveMaxMoney() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StoredUtxo(
                                StoredUtxo.MAX_MONEY + 1,
                                new byte[]{0x51},
                                1L,
                                false
                        )
        );
    }

    @Test
    void shouldRejectNegativeHeight() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new StoredUtxo(
                                1000L,
                                new byte[]{0x51},
                                -1L,
                                false
                        )
        );
    }

    @Test
    void shouldDefensivelyCopyScriptPubKey() {

        byte[] script = {
                0x51,
                0x52
        };

        StoredUtxo utxo =
                new StoredUtxo(
                        1000L,
                        script,
                        1L,
                        false
                );

        /*
         * Изменяем исходный массив после создания UTXO.
         */
        script[0] = 0x00;

        assertArrayEquals(
                new byte[]{
                        0x51,
                        0x52
                },
                utxo.scriptPubKey()
        );

        /*
         * Теперь пытаемся изменить массив,
         * возвращённый getter'ом.
         */
        byte[] returned =
                utxo.scriptPubKey();

        returned[0] = 0x00;

        assertArrayEquals(
                new byte[]{
                        0x51,
                        0x52
                },
                utxo.scriptPubKey()
        );
    }

    @Test
    void shouldCompareByValue() {

        StoredUtxo first =
                new StoredUtxo(
                        1000L,
                        new byte[]{
                                0x00,
                                0x14,
                                0x01
                        },
                        10L,
                        false
                );

        StoredUtxo second =
                new StoredUtxo(
                        1000L,
                        new byte[]{
                                0x00,
                                0x14,
                                0x01
                        },
                        10L,
                        false
                );

        assertEquals(
                first,
                second
        );

        assertEquals(
                first.hashCode(),
                second.hashCode()
        );
    }
}