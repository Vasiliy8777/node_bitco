package ru.bitcoin.node.chain;

import ru.bitcoin.node.chain.storage.KnownBlockStorage;
import ru.bitcoin.node.consensus.block.BlockValidator;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Entry point for complete blocks. All callers must share the same ChainState
 * and route chain mutations through this processor (or the same state lock).
 * Storage and validation exceptions propagate to the caller unchanged.
 */
public final class BlockProcessor {
    private static final System.Logger log =
            System.getLogger(BlockProcessor.class.getName());

    private static final long IBD_DIAGNOSTIC_HEIGHT_LIMIT = 32L;

    private final ChainState chainState;
    private final BlockIndexLookup lookup;
    private final KnownBlockStorage storage;
    private final ChainReorganizationExecutor executor;
    private final NetworkParameters parameters;
    private final AdjustedTime adjustedTime;
    private final BlockFailureManager failureManager;
    private final BlockFailureResolver failureResolver;

    // IBD diagnostics only. LongAdder keeps the instrumentation cheap and also safe if
    // callers outside the main IBD path ever invoke this processor concurrently.
    private final LongAdder diagnosticProcessed = new LongAdder();
    private final LongAdder diagnosticStructureKnownNanos = new LongAdder();
    private final LongAdder diagnosticParentHeaderNanos = new LongAdder();
    private final LongAdder diagnosticWitnessSignetNanos = new LongAdder();
    private final LongAdder diagnosticPrepareUpdateNanos = new LongAdder();
    private final LongAdder diagnosticReorgPrepareNanos = new LongAdder();
    private final LongAdder diagnosticCommitNanos = new LongAdder();

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
            diagnosticProcessed.increment();
            long phaseStarted = System.nanoTime();
            // Even a known header can arrive with a different, invalid body.
            BlockValidator.validateStructure(block);
            BlockIndex known = lookup.find(block.hash());
            diagnosticStructureKnownNanos.add(System.nanoTime() - phaseStarted);
            if (known != null && isActiveChainBlock(known)) {
                ru.bitcoin.node.consensus.block.WitnessCommitmentValidator.validate(
                        block, known.height() >= parameters.segwitHeight());
                ru.bitcoin.node.consensus.block.SignetBlockValidator.validate(block, parameters);
                // A pruned active-chain block can be received again. Preserve the validated
                // body instead of discarding it merely because its BlockIndex is already known.
                if (!storage.hasBody(known.hash())) {
                    storage.save(block, known);
                }
                return BlockProcessingResult.ALREADY_IN_ACTIVE_CHAIN;
            }

            phaseStarted = System.nanoTime();
            BlockIndex parent = lookup.find(block.header().previousBlockHash());
            if (parent == null) {
                return BlockProcessingResult.UNKNOWN_PARENT;
            }

            long diagnosticHeight = Math.addExact(parent.height(), 1L);
            logDiagnostic(diagnosticHeight, block, "structure+lookup done");

            ChainHeaderValidator.validate(
                    block.header(), parent, lookup, parameters, adjustedTime);
            diagnosticParentHeaderNanos.add(System.nanoTime() - phaseStarted);
            logDiagnostic(diagnosticHeight, block, "header validation done");

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
            phaseStarted = System.nanoTime();
            ru.bitcoin.node.consensus.block.WitnessCommitmentValidator.validate(
                    block, candidate.height() >= parameters.segwitHeight());
            ru.bitcoin.node.consensus.block.SignetBlockValidator.validate(block, parameters);
            diagnosticWitnessSignetNanos.add(System.nanoTime() - phaseStarted);
            logDiagnostic(candidate.height(), block, "witness+signet validation done");

            logDiagnostic(candidate.height(), block, "prepareUpdate start");
            phaseStarted = System.nanoTime();
            ChainUpdate update = chainState.prepareUpdate(candidate, lookup);
            diagnosticPrepareUpdateNanos.add(System.nanoTime() - phaseStarted);
            logDiagnostic(candidate.height(), block, "prepareUpdate done");
            if (update == null) {
                // Side-chain/context-pending bodies still need their own durable storage commit.
                storage.save(block, candidate);
                return BlockProcessingResult.STORED_SIDE_CHAIN_CONTEXT_PENDING;
            }

            // Prepare directly from the in-memory network block. This avoids immediately
            // reading the body back from RocksDB after saving it. The body/index/availability
            // writes are then committed atomically with the chain transition in one batch.
            PreparedChainReorganization prepared;
            try {
                logDiagnostic(candidate.height(), block, "reorg prepare start");
                phaseStarted = System.nanoTime();
                prepared = executor.prepare(update, block, invalidIndex -> {
                    // The observer receives the exact BlockIndex resolved by the prepare-phase
                    // overlay. It may not be durable yet during reindex or first connection.
                    if (invalidIndex.hash().equals(candidate.hash())
                            && !storage.hasBody(candidate.hash())) {
                        storage.save(block, candidate);
                    }
                    if (failureManager != null) {
                        failureManager.markFailed(invalidIndex);
                    }
                });
                diagnosticReorgPrepareNanos.add(System.nanoTime() - phaseStarted);
                logDiagnostic(candidate.height(), block, "reorg prepare done");
            } catch (ru.bitcoin.node.consensus.block.BlockValidationException
                     | ru.bitcoin.node.consensus.transaction.TransactionValidationException
                     | ru.bitcoin.node.script.ScriptExecutionException exception) {
                // Keep invalid/raw block data available for restart-safe diagnostics/reindex.
                // If an older side-chain ancestor was the invalid root, the incoming descendant
                // still needs its own durable BlockIndex/body even though it is not activated.
                if (!storage.hasBody(candidate.hash())) {
                    storage.save(block, candidate);
                }
                throw exception;
            }
            logDiagnostic(candidate.height(), block, "commit start");
            phaseStarted = System.nanoTime();
            executor.commitWithStagedNewTipIndex(
                    prepared, batch -> storage.save(batch, block, candidate));
            // The child was constructed and committed in this thread. Publish it to the
            // in-memory lookup immediately so block N+1 never has to reload parent N.
            if (lookup instanceof StoredBlockIndexLookup storedLookup) {
                storedLookup.rememberCommitted(candidate);
            }
            diagnosticCommitNanos.add(System.nanoTime() - phaseStarted);
            logDiagnostic(candidate.height(), block, "commit done");

            return BlockProcessingResult.CONNECTED;
        }
    }

    public Bip34AncestryCache.DiagnosticSnapshot bip34AncestryDiagnosticSnapshot() {
        return executor.bip34AncestryDiagnosticSnapshot();
    }

    public DiagnosticSnapshot diagnosticSnapshot() {
        return new DiagnosticSnapshot(
                diagnosticProcessed.sum(),
                diagnosticStructureKnownNanos.sum(),
                diagnosticParentHeaderNanos.sum(),
                diagnosticWitnessSignetNanos.sum(),
                diagnosticPrepareUpdateNanos.sum(),
                diagnosticReorgPrepareNanos.sum(),
                diagnosticCommitNanos.sum()
        );
    }

    public record DiagnosticSnapshot(
            long processed,
            long structureKnownNanos,
            long parentHeaderNanos,
            long witnessSignetNanos,
            long prepareUpdateNanos,
            long reorgPrepareNanos,
            long commitNanos
    ) {
        public DiagnosticSnapshot minus(DiagnosticSnapshot baseline) {
            Objects.requireNonNull(baseline, "baseline");
            return new DiagnosticSnapshot(
                    processed - baseline.processed,
                    structureKnownNanos - baseline.structureKnownNanos,
                    parentHeaderNanos - baseline.parentHeaderNanos,
                    witnessSignetNanos - baseline.witnessSignetNanos,
                    prepareUpdateNanos - baseline.prepareUpdateNanos,
                    reorgPrepareNanos - baseline.reorgPrepareNanos,
                    commitNanos - baseline.commitNanos
            );
        }
    }

    private static void logDiagnostic(
            long height,
            Block block,
            String stage
    ) {
        // Detailed per-block IBD tracing is intentionally disabled in production.
        // Keep the call sites cheap so diagnostics can be restored locally when needed.
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
