package ru.bitcoin.node.chain;

/**
 * A block-index lookup capable of branch-safe logarithmic ancestor traversal.
 */
public interface BlockIndexAncestorLookup extends BlockIndexLookup {
    BlockIndex ancestor(BlockIndex index, long targetHeight);

    default BlockIndex ancestor(BlockIndex index, long targetHeight, Runnable checkpoint) {
        checkpoint.run();
        BlockIndex result = ancestor(index, targetHeight);
        checkpoint.run();
        return result;
    }
}
