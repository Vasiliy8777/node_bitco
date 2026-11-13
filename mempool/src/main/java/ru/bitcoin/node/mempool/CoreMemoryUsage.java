package ru.bitcoin.node.mempool;

import java.util.Collection;
import java.util.List;
import ru.bitcoin.node.protocol.transaction.Transaction;

/** Bitcoin Core's deterministic release x64 estimate, independent of JVM allocation. */
final class CoreMemoryUsage {
    private CoreMemoryUsage() { }

    static long allocation(long bytes) {
        if (bytes < 0) throw new IllegalArgumentException("Negative allocation");
        return bytes == 0 ? 0 : Math.addExact(bytes, 31) / 16 * 16;
    }

    /** RecursiveDynamicUsage(CTransactionRef), for a freshly decoded transaction. */
    static long transaction(Transaction tx) {
        long total = allocation(128) + allocation(24)
                + allocation(112L * tx.inputs().size()) + allocation(48L * tx.outputs().size());
        for (var input : tx.inputs()) {
            if (input.scriptSig().length > 36) total += allocation(input.scriptSig().length);
            total += allocation(24L * input.witness().size());
            for (int i=0;i<input.witness().size();i++) total += allocation(input.witness().item(i).length);
        }
        for (var output : tx.outputs())
            if (output.scriptPubKey().length > 36) total += allocation(output.scriptPubKey().length);
        return total;
    }

    static long entry(MempoolEntry entry) {
        // CTxMemPoolEntry=136 plus nine index pointers; mapNextTx has 48-byte nodes.
        return allocation(136 + 9 * 8) + allocation(48) * entry.transaction().inputs().size()
                + transaction(entry.transaction());
    }

    static long randomizedCapacity(long capacity, int size, int removed, int added) {
        if (capacity < size || size < 0 || removed < 0 || removed > size || added < 0)
            throw new IllegalArgumentException("Invalid randomized-index transition");
        for (int i=0;i<removed;i++) {
            size--;
            // Core deliberately keeps the allocation when clearing the last element.
            if (size > 0 && 2L * size < capacity) capacity = size;
        }
        for (int i=0;i<added;i++) {
            size++;
            if (size > capacity) capacity = Math.max(size, Math.multiplyExact(2, capacity));
        }
        return capacity;
    }

    /** Core compacts materialized components after merging/splitting/removing.
     * Release x64 layout: Entry=80, SingletonClusterImpl=48, GenericClusterImpl=104,
     * DepGraph::Entry=32, graph indices=4, ChunkData=8.
     * A memusage tree node adds 32 bytes; a cluster-vector slot adds 8 bytes.
     */
    static long graph(Collection<? extends List<ClusterLinearization.Chunk>> clusters) {
        long total = 0;
        for (var chunks : clusters) {
            long count = chunks.stream().mapToLong(chunk -> chunk.transactions().size()).sum();
            if (count == 0) continue;
            total = Math.addExact(total, component(count,chunks.size()));
        }
        return total;
    }
    static long component(long count, int indexedChunks) {
        if (count == 0) return 0;
        long usage = Math.addExact(Math.multiplyExact(80,count),Math.multiplyExact(allocation(40),indexedChunks));
        return Math.addExact(usage,count == 1 ? allocation(48) + 8
                : allocation(104) + 8 + 2 * allocation(4 * count) + allocation(32 * count));
    }
}
