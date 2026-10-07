package ru.bitcoin.node.chain;

import ru.bitcoin.node.consensus.block.Bip34Validator;
import ru.bitcoin.node.consensus.transaction.TransactionFinality;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParameters;

/** Header-context body checks from Core ContextualCheckBlock; does not access UTXOs or write state. */
public final class ContextualBlockReceptionValidator {
    private ContextualBlockReceptionValidator() { }

    /** Caller must first check the header, structure, merkle root and witness commitment. */
    public static void validate(Block block, BlockIndex candidate, BlockIndex parent,
                                BlockIndexLookup lookup, NetworkParameters parameters) {
        java.util.Objects.requireNonNull(block);
        java.util.Objects.requireNonNull(candidate);
        java.util.Objects.requireNonNull(parent);
        java.util.Objects.requireNonNull(lookup);
        java.util.Objects.requireNonNull(parameters);
        if (!block.hash().equals(candidate.hash())
                || !candidate.previousBlockHash().equals(parent.hash())
                || candidate.height() != Math.addExact(parent.height(), 1L))
            throw new IllegalArgumentException("Block reception context does not match header ancestry");
        long cutoff = candidate.height() >= parameters.csvHeight()
                ? MedianTimePast.calculate(parent, lookup) : block.header().timestamp().value();
        for (var transaction : block.transactions())
            TransactionFinality.validate(transaction, candidate.height(), cutoff);
        Bip34Validator.validate(block.transactions().getFirst(), candidate.height(), parameters);
    }
}
