package ru.bitcoin.node.chain;

import ru.bitcoin.node.chain.storage.KnownBlockStorage;
import ru.bitcoin.node.consensus.block.BlockValidator;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.util.Objects;

/**
 * Entry point for complete blocks. All callers must share the same ChainState
 * and route chain mutations through this processor (or the same state lock).
 * Storage and validation exceptions propagate to the caller unchanged.
 */
public final class BlockProcessor {
    private final ChainState chainState;
    private final BlockIndexLookup lookup;
    private final KnownBlockStorage storage;
    private final ChainReorganizationExecutor executor;
    private final NetworkParameters parameters;
    private final AdjustedTime adjustedTime;
    private final BlockFailureManager failureManager;
    private final BlockFailureResolver failureResolver;

    public BlockProcessor(
            ChainState chainState,
            BlockIndexLookup lookup,
            KnownBlockStorage storage,
            ChainReorganizationExecutor executor,
            NetworkParameters parameters,
            AdjustedTime adjustedTime
    ) {
        this(
                chainState,
                lookup,
                storage,
                executor,
                parameters,
                adjustedTime,
                null,
                null
        );
    }

    public BlockProcessor(
            ChainState chainState,
            BlockIndexLookup lookup,
            KnownBlockStorage storage,
            ChainReorganizationExecutor executor,
            NetworkParameters parameters,
            AdjustedTime adjustedTime,
            BlockFailureManager failureManager,
            BlockFailureResolver failureResolver
    ) {
        this.chainState =
                Objects.requireNonNull(
                        chainState,
                        "chainState"
                );

        this.lookup =
                Objects.requireNonNull(
                        lookup,
                        "lookup"
                );

        this.storage =
                Objects.requireNonNull(
                        storage,
                        "storage"
                );

        this.executor =
                Objects.requireNonNull(
                        executor,
                        "executor"
                );

        this.parameters =
                Objects.requireNonNull(
                        parameters,
                        "parameters"
                );

        this.adjustedTime =
                Objects.requireNonNull(
                        adjustedTime,
                        "adjustedTime"
                );

        this.failureManager =
                failureManager;

        this.failureResolver =
                failureResolver;
    }

    public BlockProcessingResult process(Block block) {
        Objects.requireNonNull(block, "block");
        synchronized (chainState) {
            // Even a known header can arrive with a different, invalid body.
            BlockValidator.validateStructure(block);
            BlockIndex known = lookup.find(block.hash());
            if (known != null && isActiveChainBlock(known)) {
                ru.bitcoin.node.consensus.block.WitnessCommitmentValidator.validate(
                        block, known.height() >= parameters.segwitHeight());
                ru.bitcoin.node.consensus.block.SignetBlockValidator.validate(block, parameters);
                return BlockProcessingResult.ALREADY_IN_ACTIVE_CHAIN;
            }

            BlockIndex parent = lookup.find(block.header().previousBlockHash());
            if (parent == null) {
                return BlockProcessingResult.UNKNOWN_PARENT;
            }
            ChainHeaderValidator.validate(
                    block.header(), parent, lookup, parameters, adjustedTime);
            BlockIndex candidate = BlockIndexFactory.createChild(parent, block.header());
            if (failureResolver != null
                    && failureResolver.isFailed(
                    candidate
            )) {
                throw new IllegalArgumentException(
                        "Block belongs to a permanently failed chain: "
                                + candidate.hash()
                                .toDisplayHex()
                );
            }
            ru.bitcoin.node.consensus.block.WitnessCommitmentValidator.validate(
                    block, candidate.height() >= parameters.segwitHeight());
            ru.bitcoin.node.consensus.block.SignetBlockValidator.validate(block, parameters);

            // A stored body is not proof of contextual validity. Re-save the
            // supplied body and retry activation, including after a prior failure.
            storage.save(block, candidate);
            ChainUpdate update = chainState.prepareUpdate(candidate, lookup);
            if (update == null) {
                return BlockProcessingResult.STORED_SIDE_CHAIN_CONTEXT_PENDING;
            }

            executor.execute(
                    update
            );

            return BlockProcessingResult.CONNECTED;
        }
    }

    private boolean isActiveChainBlock(BlockIndex candidate) {
        BlockIndex current = chainState.activeTip();
        while (current.height() > candidate.height()) {
            current = lookup.find(current.previousBlockHash());
            if (current == null) {
                throw new IllegalStateException("Missing active-chain ancestor");
            }
        }
        return current.hash().equals(candidate.hash());
    }
}
