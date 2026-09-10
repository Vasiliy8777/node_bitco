package ru.bitcoin.node.protocol.serialization.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;
import ru.bitcoin.node.protocol.transaction.*;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlockTest {

    @Test
    void shouldSerializeAndParseBlock() {

        Transaction transaction =
                createTransaction();

        BlockHeader header =
                new BlockHeader(
                        1,
                        new Hash256(new byte[32]),
                        transaction.txId(),
                        new UInt32(1231006505L),
                        new UInt32(0x1D00FFFFL),
                        new UInt32(1)
                );

        Block original =
                new Block(
                        header,
                        List.of(transaction)
                );

        byte[] serialized =
                BlockSerializer.serialize(
                        original
                );

        Block parsed =
                BlockParser.parse(
                        serialized
                );

        assertEquals(
                original.hash(),
                parsed.hash()
        );

        assertEquals(
                1,
                parsed.transactions().size()
        );

        assertEquals(
                transaction.txId(),
                parsed.transactions()
                        .getFirst()
                        .txId()
        );

        assertArrayEquals(
                serialized,
                BlockSerializer.serialize(parsed)
        );
    }

    private static Transaction createTransaction() {

        TxIn input =
                new TxIn(
                        OutPoint.coinbase(),
                        HexUtils.decode("0101"),
                        TxIn.FINAL_SEQUENCE
                );

        TxOut output =
                new TxOut(
                        5_000_000_000L,
                        HexUtils.decode("51")
                );

        return new Transaction(
                1,
                List.of(input),
                List.of(output),
                new UInt32(0)
        );
    }
}
