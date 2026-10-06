package ru.bitcoin.node.protocol.serialization;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Independent byte-serialization oracle for a proposed allocation-free size calculation. */
class WireSizeEquivalenceTest {
    @Test void matchesCompactSizeWidthsAndBlockTransactionCounts() {
        for (long value : new long[]{0, 252, 253, 65535, 65536, 0xffffffffL, 0x100000000L, Long.MAX_VALUE})
            assertEquals(ru.bitcoin.node.common.encoding.CompactSize.encode(value).length,
                    ru.bitcoin.node.common.encoding.CompactSize.encodedSize(value));
        var genesis = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
        for (int count : new int[]{0, 1, 252, 253, 65535, 65536}) {
            var block = new Block(genesis.header(), Collections.nCopies(count, genesis.transactions().getFirst()));
            assertEquals(BlockSerializer.serializeLegacy(block).length, BlockSerializer.serializedSize(block, false));
            assertEquals(BlockSerializer.serialize(block).length, BlockSerializer.serializedSize(block, true));
        }
    }

    @Test void measuresAllocationOnIdenticalWitnessBlock() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Boolean.getBoolean("ibd.benchmark.wire.size"));
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        org.junit.jupiter.api.Assumptions.assumeTrue(bean.isThreadAllocatedMemorySupported());
        bean.setThreadAllocatedMemoryEnabled(true);
        var genesis = GenesisBlockFactory.create(NetworkParametersRegistry.regtest());
        var block = new Block(genesis.header(), Collections.nCopies(128, transaction(200, 128, 2)));
        long[] sums = new long[2];
        for (int mode = 0; mode < 2; mode++) {
            for (int warm = 0; warm < 20; warm++) {
                if (mode == 0) { BlockSerializer.serializeLegacy(block); BlockSerializer.serialize(block); }
                else { BlockSerializer.serializedSize(block, false); BlockSerializer.serializedSize(block, true); }
            }
            long thread = Thread.currentThread().threadId();
            long bytes = bean.getThreadAllocatedBytes(thread);
            long started = System.nanoTime();
            for (int repeat = 0; repeat < 100; repeat++) {
                sums[mode] += mode == 0
                        ? BlockSerializer.serializeLegacy(block).length + BlockSerializer.serialize(block).length
                        : BlockSerializer.serializedSize(block, false) + BlockSerializer.serializedSize(block, true);
            }
            System.out.printf(Locale.ROOT, "WIRE SIZE mode=%s elapsedMs=%.3f allocatedBytes=%d sum=%d%n",
                    mode == 0 ? "serialize" : "count", (System.nanoTime() - started) / 1_000_000.0,
                    bean.getThreadAllocatedBytes(thread) - bytes, sums[mode]);
        }
        assertEquals(sums[0], sums[1]);
    }
    private static int compact(long value) {
        if (value < 253) return 1;
        if (value <= 65535) return 3;
        if (value <= 0xffffffffL) return 5;
        return 9;
    }

    private static long proposedSize(Transaction tx, boolean includeWitness) {
        boolean witness = includeWitness && tx.hasWitness();
        long size = 8L + compact(tx.inputs().size()) + compact(tx.outputs().size()) + (witness ? 2 : 0);
        for (TxIn input : tx.inputs()) {
            int length = input.scriptSig().length;
            size += 40L + compact(length) + length;
        }
        for (TxOut output : tx.outputs()) {
            int length = output.scriptPubKey().length;
            size += 8L + compact(length) + length;
        }
        if (witness) for (TxIn input : tx.inputs()) {
            size += compact(input.witness().size());
            for (byte[] item : input.witness().items()) size += compact(item.length) + item.length;
        }
        return size;
    }

    private static void check(Transaction tx) {
        assertEquals(TransactionSerializer.serializeLegacy(tx).length, proposedSize(tx, false));
        assertEquals(TransactionSerializer.serialize(tx).length, proposedSize(tx, true));
        assertEquals(TransactionSerializer.serializeLegacy(tx).length,
                TransactionSerializer.serializedSize(tx, false));
        assertEquals(TransactionSerializer.serialize(tx).length,
                TransactionSerializer.serializedSize(tx, true));
    }

    private static Transaction transaction(int scriptLength, int witnessLength, int count) {
        var input = new TxIn(OutPoint.coinbase(), new byte[scriptLength], TxIn.FINAL_SEQUENCE,
                new Witness(List.of(new byte[witnessLength])));
        var emptyWitnessInput = new TxIn(OutPoint.coinbase(), new byte[0], TxIn.FINAL_SEQUENCE);
        var inputs = new ArrayList<TxIn>(Collections.nCopies(count, input));
        inputs.add(emptyWitnessInput);
        return new Transaction(2, inputs,
                Collections.nCopies(count, new TxOut(1, new byte[scriptLength])), new UInt32(0));
    }

    @Test void matchesSerializationAtCompactSizeBoundaries() {
        int[] boundaries = {0, 1, 251, 252, 253, 254, 65534, 65535, 65536};
        for (int length : boundaries) check(transaction(length, length, 1));
        for (int count : boundaries) check(transaction(0, 0, count));
        for (int count : boundaries) {
            var input = new TxIn(OutPoint.coinbase(), new byte[0], TxIn.FINAL_SEQUENCE,
                    new Witness(Collections.nCopies(count, new byte[0])));
            check(new Transaction(2, List.of(input), List.of(new TxOut(1, new byte[0])), new UInt32(0)));
        }
    }

    @Test void matchesMixedLegacyAndWitnessBlocks() {
        var random = new Random(20261006L);
        var header = GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header();
        for (int i = 0; i < 200; i++) {
            var tx = transaction(random.nextInt(1024), random.nextInt(1024), random.nextInt(8));
            check(tx);
            var legacy = new Transaction(1, List.of(new TxIn(OutPoint.coinbase(), new byte[]{1, 1},
                    TxIn.FINAL_SEQUENCE)), List.of(new TxOut(1, new byte[]{0x51})), new UInt32(0));
            var block = new Block(header, List.of(legacy, tx));
            long stripped = 80L + compact(2) + proposedSize(legacy, false) + proposedSize(tx, false);
            long total = 80L + compact(2) + proposedSize(legacy, true) + proposedSize(tx, true);
            assertEquals(BlockSerializer.serializeLegacy(block).length, stripped);
            assertEquals(BlockSerializer.serialize(block).length, total);
            assertEquals(stripped, BlockSerializer.serializedSize(block, false));
            assertEquals(total, BlockSerializer.serializedSize(block, true));
        }
    }
}
