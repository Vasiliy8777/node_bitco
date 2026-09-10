package ru.bitcoin.node.protocol.serialization;

import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.ArrayList;
import java.util.List;

public final class BlockParser {

    private BlockParser() {
    }

    public static Block parse(
            byte[] bytes
    ) {
        if (bytes == null) {
            throw new IllegalArgumentException(
                    "bytes must not be null"
            );
        }

        BitcoinReader reader =
                new BitcoinReader(bytes);

        Block block =
                parse(reader);

        if (reader.hasRemaining()) {
            throw new IllegalArgumentException(
                    "Unexpected bytes after block"
            );
        }

        return block;
    }

    public static Block parse(
            BitcoinReader reader
    ) {
        if (reader == null) {
            throw new IllegalArgumentException(
                    "reader must not be null"
            );
        }

        BlockHeader header =
                BlockHeaderParser.parse(reader);

        long transactionCount =
                reader.readCompactSize();

        if (transactionCount > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    "Transaction count is too large"
            );
        }

        int count =
                (int) transactionCount;

        List<Transaction> transactions =
                new ArrayList<>(count);

        for (int i = 0; i < count; i++) {

            transactions.add(
                    TransactionParser.parse(reader)
            );
        }

        return new Block(
                header,
                transactions
        );
    }
}