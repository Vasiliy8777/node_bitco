package ru.bitcoin.node.protocol.serialization;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class TransactionSerializerTest {
//Тест простой legacy-транзакции
    @Test
    void shouldSerializeLegacyTransaction() {

        Hash256 previousTxId =
                Hash256.fromDisplayHex(
                        "0000000000000000000000000000000000000000000000000000000000000001"
                );

        TxIn input =
                new TxIn(
                        new OutPoint(
                                previousTxId,
                                new UInt32(2)
                        ),
                        HexUtils.decode("5152"),
                        TxIn.FINAL_SEQUENCE
                );

        TxOut output =
                new TxOut(
                        50_000,
                        HexUtils.decode("51")
                );

        Transaction transaction =
                new Transaction(
                        2,
                        List.of(input),
                        List.of(output),
                        new UInt32(0)
                );

        byte[] serialized =
                TransactionSerializer.serialize(
                        transaction
                );

        assertFalse(
                transaction.hasWitness()
        );

        assertEquals(
                TransactionSerializer
                        .serializeLegacy(transaction)
                        .length,
                serialized.length
        );
    }
    // round-trip тест
    @Test
    void shouldSerializeAndParseLegacyTransaction() {

        Hash256 previousTxId =
                Hash256.fromDisplayHex(
                        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                );

        TxIn input =
                new TxIn(
                        new OutPoint(
                                previousTxId,
                                new UInt32(3)
                        ),
                        HexUtils.decode("515253"),
                        new UInt32(0xFFFF_FFFEL)
                );

        TxOut output =
                new TxOut(
                        123_456_789L,
                        HexUtils.decode("76a914")
                );

        Transaction original =
                new Transaction(
                        2,
                        List.of(input),
                        List.of(output),
                        new UInt32(500)
                );

        byte[] serialized =
                TransactionSerializer.serialize(
                        original
                );

        Transaction parsed =
                TransactionParser.parse(
                        serialized
                );

        assertEquals(
                original.version(),
                parsed.version()
        );

        assertEquals(
                original.inputs().size(),
                parsed.inputs().size()
        );

        assertEquals(
                previousTxId,
                parsed.inputs()
                        .getFirst()
                        .previousOutput()
                        .transactionId()
        );

        assertEquals(
                3,
                parsed.inputs()
                        .getFirst()
                        .previousOutput()
                        .outputIndex()
                        .value()
        );

        assertArrayEquals(
                HexUtils.decode("515253"),
                parsed.inputs()
                        .getFirst()
                        .scriptSig()
        );

        assertEquals(
                123_456_789L,
                parsed.outputs()
                        .getFirst()
                        .value()
        );

        assertEquals(
                500,
                parsed.lockTime().value()
        );

        /*
         * Самая сильная проверка:
         *
         * parse -> serialize должен вернуть
         * абсолютно те же байты.
         */
        assertArrayEquals(
                serialized,
                TransactionSerializer.serialize(parsed)
        );
    }
    //SegWit тест
    @Test
    void shouldSerializeAndParseWitnessTransaction() {

        Hash256 previousTxId =
                Hash256.fromDisplayHex(
                        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                );

        Witness witness =
                new Witness(
                        List.of(
                                HexUtils.decode("30440201"),
                                HexUtils.decode("0279be66")
                        )
                );

        TxIn input =
                new TxIn(
                        new OutPoint(
                                previousTxId,
                                new UInt32(0)
                        ),
                        new byte[0],
                        TxIn.FINAL_SEQUENCE,
                        witness
                );

        TxOut output =
                new TxOut(
                        100_000,
                        HexUtils.decode(
                                "0014751e76e8199196d454941c45d1b3a323f1433bd6"
                        )
                );

        Transaction original =
                new Transaction(
                        2,
                        List.of(input),
                        List.of(output),
                        new UInt32(0)
                );

        byte[] serialized =
                TransactionSerializer.serialize(
                        original
                );

        /*
         * После 4-byte version:
         *
         * byte[4] = marker 00
         * byte[5] = flag   01
         */
        assertEquals(
                0x00,
                serialized[4] & 0xFF
        );

        assertEquals(
                0x01,
                serialized[5] & 0xFF
        );

        Transaction parsed =
                TransactionParser.parse(
                        serialized
                );

        assertTrue(parsed.hasWitness());

        assertEquals(
                2,
                parsed.inputs()
                        .getFirst()
                        .witness()
                        .size()
        );

        assertArrayEquals(
                witness.item(0),
                parsed.inputs()
                        .getFirst()
                        .witness()
                        .item(0)
        );

        assertArrayEquals(
                serialized,
                TransactionSerializer.serialize(parsed)
        );
    }
    //Проверка txid != wtxid
    @Test
    void witnessTransactionShouldHaveDifferentTxIdAndWtxId() {

        TxIn input =
                new TxIn(
                        new OutPoint(
                                new Hash256(new byte[32]),
                                new UInt32(1)
                        ),
                        new byte[0],
                        TxIn.FINAL_SEQUENCE,
                        new Witness(
                                List.of(
                                        HexUtils.decode("010203")
                                )
                        )
                );

        TxOut output =
                new TxOut(
                        1_000,
                        HexUtils.decode("51")
                );

        Transaction transaction =
                new Transaction(
                        2,
                        List.of(input),
                        List.of(output),
                        new UInt32(0)
                );

        assertNotEquals(
                transaction.txId(),
                transaction.wtxId()
        );
    }
    //Проверка txid != wtxid для legacy:
    @Test
    void legacyTransactionShouldHaveSameTxIdAndWtxId() {

        TxIn input =
                new TxIn(
                        new OutPoint(
                                new Hash256(new byte[32]),
                                new UInt32(1)
                        ),
                        new byte[0],
                        TxIn.FINAL_SEQUENCE
                );

        TxOut output =
                new TxOut(
                        1_000,
                        HexUtils.decode("51")
                );

        Transaction transaction =
                new Transaction(
                        1,
                        List.of(input),
                        List.of(output),
                        new UInt32(0)
                );

        assertEquals(
                transaction.txId(),
                transaction.wtxId()
        );
    }
    //проверка coinbase OutPoint
    @Test
    void shouldRecognizeCoinbaseOutPoint() {

        OutPoint outPoint =
                OutPoint.coinbase();

        assertTrue(
                outPoint.isCoinbase()
        );

        assertEquals(
                0xFFFF_FFFFL,
                outPoint.outputIndex().value()
        );

        assertEquals(
                "0000000000000000000000000000000000000000000000000000000000000000",
                outPoint.transactionId().toHex()
        );
    }
}
