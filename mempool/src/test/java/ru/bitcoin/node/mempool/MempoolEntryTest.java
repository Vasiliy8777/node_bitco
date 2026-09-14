package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.protocol.transaction.Witness;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MempoolEntryTest {

    @Test
    void mustExposeVirtualSizeAndFeeRate() {

        Transaction transaction =
                new Transaction(
                        2,
                        List.of(
                                new TxIn(
                                        new OutPoint(
                                                Hash256.fromDisplayHex(
                                                        "11".repeat(32)
                                                ),
                                                new UInt32(0L)
                                        ),
                                        new byte[0],
                                        new UInt32(
                                                0xffff_fffeL
                                        ),
                                        Witness.EMPTY
                                )
                        ),
                        List.of(
                                new TxOut(
                                        90_000L,
                                        new byte[]{
                                                0x51
                                        }
                                )
                        ),
                        new UInt32(0L)
                );

        MempoolEntry entry =
                new MempoolEntry(
                        transaction,
                        250L,
                        401L,
                        123456L
                );

        /*
         * ceil(401 / 4) = 101
         */
        assertEquals(
                101L,
                entry.virtualSize()
        );

        /*
         * 250 * 1000 / 101 = 2475
         */
        assertEquals(
                2_475L,
                entry.feeRate()
                        .satoshisPerKiloByte()
        );
    }
}