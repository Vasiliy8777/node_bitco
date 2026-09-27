package ru.bitcoin.node.app;

import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.filter.BasicBlockFilter;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.storage.block.RocksDbBlockStore;
import ru.bitcoin.node.storage.blockfilter.RocksDbBlockFilterIndexStore;
import ru.bitcoin.node.storage.undo.BlockUndoData;
import ru.bitcoin.node.storage.undo.RocksDbUndoStore;

import java.util.*;

/** Builds and synchronizes the optional BIP158 basic block-filter index. */
final class BlockFilterIndexService {
    private static final Hash256 ZERO = new Hash256(new byte[32]);
    private final RocksDbBlockFilterIndexStore store;
    private final RocksDbBlockStore blocks;
    private final RocksDbUndoStore undos;
    private final BlockIndexLookup lookup;
    private final ActiveChainAncestors ancestors;

    BlockFilterIndexService(RocksDbBlockFilterIndexStore store, RocksDbBlockStore blocks,
                            RocksDbUndoStore undos, BlockIndexLookup lookup, ActiveChainAncestors ancestors) {
        this.store = Objects.requireNonNull(store);
        this.blocks = Objects.requireNonNull(blocks);
        this.undos = Objects.requireNonNull(undos);
        this.lookup = Objects.requireNonNull(lookup);
        this.ancestors = Objects.requireNonNull(ancestors);
    }

    Optional<RocksDbBlockFilterIndexStore.Record> find(Hash256 hash) { return store.find(hash); }

    void synchronize(BlockIndex activeTip) {
        Hash256 cursor = store.bestIndexedBlockHash().orElse(null);
        if (cursor == null) {
            BlockIndex genesis = ancestors.at(activeTip, 0L, lookup);
            if (genesis == null) throw new IllegalStateException("Cannot initialize blockfilterindex without genesis");
            connect(genesis);
            cursor = genesis.hash();
        }
        BlockIndex indexed = lookup.find(cursor);
        if (indexed == null) throw new IllegalStateException("blockfilterindex cursor references unknown block");
        ReorganizationPlan plan = ReorganizationPlanner.plan(indexed, activeTip, lookup);
        for (BlockIndex disconnect : plan.blocksToDisconnect()) {
            BlockIndex parent = lookup.find(disconnect.previousBlockHash());
            if (parent == null) throw new IllegalStateException("Missing blockfilterindex reorg parent");
            store.moveCursor(parent.hash());
        }
        for (BlockIndex connect : plan.blocksToConnect()) {
            if (store.find(connect.hash()).isPresent()) store.moveCursor(connect.hash());
            else connect(connect);
        }
        if (!store.bestIndexedBlockHash().orElseThrow().equals(activeTip.hash()))
            throw new IllegalStateException("blockfilterindex synchronization stopped before active tip");
    }

    private void connect(BlockIndex index) {
        Block block = blocks.find(index.hash()).orElseThrow(() ->
                new IllegalStateException("Block body required by blockfilterindex: " + index.hash().toDisplayHex()));
        BlockUndoData undo = index.height() == 0 ? new BlockUndoData(List.of()) : undos.find(index.hash()).orElseThrow(() ->
                new IllegalStateException("Undo data required by blockfilterindex: " + index.hash().toDisplayHex()));
        byte[] filter = buildFilter(block, undo);
        Hash256 filterHash = Hash256Digest.hash(filter);
        Hash256 previousHeader = ZERO;
        if (index.height() > 0) {
            previousHeader = store.find(index.previousBlockHash()).orElseThrow(() ->
                    new IllegalStateException("Previous block-filter header is missing")).header();
        }
        byte[] material = new byte[64];
        System.arraycopy(filterHash.bytes(), 0, material, 0, 32);
        System.arraycopy(previousHeader.bytes(), 0, material, 32, 32);
        store.append(index.hash(), filter, Hash256Digest.hash(material));
    }

    static byte[] buildFilter(Block block, BlockUndoData undo) {
        Objects.requireNonNull(block, "block");
        Objects.requireNonNull(undo, "undo");
        var elements = new TreeSet<byte[]>(Arrays::compareUnsigned);
        int undoIndex = 0;
        for (var tx : block.transactions()) {
            if (!tx.isCoinbase()) {
                if (undoIndex >= undo.transactions().size()) throw new IllegalStateException("Block-filter undo/input mismatch");
                var txUndo = undo.transactions().get(undoIndex++);
                if (txUndo.spentOutputs().size() != tx.inputs().size()) throw new IllegalStateException("Block-filter undo/input mismatch");
                for (var spent : txUndo.spentOutputs()) {
                    byte[] script = spent.scriptPubKey();
                    if (script.length != 0) elements.add(script);
                }
            }
            for (var output : tx.outputs()) {
                byte[] script = output.scriptPubKey();
                if (script.length != 0 && Byte.toUnsignedInt(script[0]) != 0x6a) elements.add(script);
            }
        }
        if (undoIndex != undo.transactions().size()) throw new IllegalStateException("Unused block-filter undo entries");
        return BasicBlockFilter.encode(block.hash(), elements);
    }
}
