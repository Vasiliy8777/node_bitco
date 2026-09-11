package ru.bitcoin.node.storage.utxo;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UtxoChangesTest {

    @Test
    void shouldCreateUtxoChanges() {

        OutPoint spent =
                outPoint(
                        'a',
                        0
                );

        CreatedUtxo created =
                new CreatedUtxo(
                        outPoint(
                                'b',
                                1
                        ),
                        utxo(
                                20_000L
                        )
                );

        UtxoChanges changes =
                new UtxoChanges(
                        List.of(
                                spent
                        ),
                        List.of(
                                created
                        )
                );

        assertEquals(
                List.of(spent),
                changes.spentOutputs()
        );

        assertEquals(
                List.of(created),
                changes.createdOutputs()
        );
    }

    @Test
    void shouldSupportEmptyChanges() {

        UtxoChanges changes =
                new UtxoChanges(
                        List.of(),
                        List.of()
                );

        assertTrue(
                changes.spentOutputs()
                        .isEmpty()
        );

        assertTrue(
                changes.createdOutputs()
                        .isEmpty()
        );
    }

    @Test
    void shouldRejectDuplicateSpentOutPoint() {

        OutPoint outPoint =
                outPoint(
                        'a',
                        0
                );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new UtxoChanges(
                                List.of(
                                        outPoint,
                                        outPoint
                                ),
                                List.of()
                        )
        );
    }

    @Test
    void shouldRejectDuplicateCreatedOutPoint() {

        OutPoint outPoint =
                outPoint(
                        'b',
                        0
                );

        CreatedUtxo first =
                new CreatedUtxo(
                        outPoint,
                        utxo(
                                1000L
                        )
                );

        CreatedUtxo second =
                new CreatedUtxo(
                        outPoint,
                        utxo(
                                2000L
                        )
                );

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new UtxoChanges(
                                List.of(),
                                List.of(
                                        first,
                                        second
                                )
                        )
        );
    }

    @Test
    void shouldAllowSameOutPointToBeCreatedAndSpent() {

        OutPoint outPoint =
                outPoint(
                        'c',
                        0
                );

        CreatedUtxo created =
                new CreatedUtxo(
                        outPoint,
                        utxo(
                                1000L
                        )
                );

        assertDoesNotThrow(
                () ->
                        new UtxoChanges(
                                List.of(
                                        outPoint
                                ),
                                List.of(
                                        created
                                )
                        )
        );
    }

    @Test
    void shouldDefensivelyCopyLists() {

        List<OutPoint> spent =
                new ArrayList<>();

        spent.add(
                outPoint(
                        'a',
                        0
                )
        );

        List<CreatedUtxo> created =
                new ArrayList<>();

        created.add(
                new CreatedUtxo(
                        outPoint(
                                'b',
                                0
                        ),
                        utxo(
                                1000L
                        )
                )
        );

        UtxoChanges changes =
                new UtxoChanges(
                        spent,
                        created
                );

        spent.clear();
        created.clear();

        assertEquals(
                1,
                changes.spentOutputs()
                        .size()
        );

        assertEquals(
                1,
                changes.createdOutputs()
                        .size()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        changes.spentOutputs()
                                .clear()
        );

        assertThrows(
                UnsupportedOperationException.class,
                () ->
                        changes.createdOutputs()
                                .clear()
        );
    }

    @Test
    void shouldRejectNullElements() {

        List<OutPoint> spent =
                new ArrayList<>();

        spent.add(null);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new UtxoChanges(
                                spent,
                                List.of()
                        )
        );

        List<CreatedUtxo> created =
                new ArrayList<>();

        created.add(null);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new UtxoChanges(
                                List.of(),
                                created
                        )
        );
    }

    private static StoredUtxo utxo(
            long amount
    ) {
        return new StoredUtxo(
                amount,
                new byte[]{0x51},
                100L,
                false
        );
    }

    private static OutPoint outPoint(
            char hexCharacter,
            long index
    ) {
        String hex =
                String.valueOf(
                        hexCharacter
                ).repeat(64);

        return new OutPoint(
                Hash256.fromDisplayHex(
                        hex
                ),
                new UInt32(index)
        );
    }
}