package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;

import static org.junit.jupiter.api.Assertions.*;

class CreatedUtxoTest {

    @Test
    void shouldCreateCreatedUtxo() {

        OutPoint outPoint =
                outPoint(0);

        StoredUtxo utxo =
                new StoredUtxo(
                        10_000L,
                        new byte[]{0x51},
                        100L,
                        false
                );

        CreatedUtxo created =
                new CreatedUtxo(
                        outPoint,
                        utxo
                );

        assertEquals(
                outPoint,
                created.outPoint()
        );

        assertEquals(
                utxo,
                created.utxo()
        );
    }

    @Test
    void shouldRejectNullOutPoint() {

        StoredUtxo utxo =
                new StoredUtxo(
                        1000L,
                        new byte[]{0x51},
                        1L,
                        false
                );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CreatedUtxo(
                                null,
                                utxo
                        )
        );
    }

    @Test
    void shouldRejectNullUtxo() {

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CreatedUtxo(
                                outPoint(0),
                                null
                        )
        );
    }

    private static OutPoint outPoint(
            long index
    ) {
        return new OutPoint(
                Hash256.fromDisplayHex(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" +
                                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                ),
                new UInt32(index)
        );
    }
}