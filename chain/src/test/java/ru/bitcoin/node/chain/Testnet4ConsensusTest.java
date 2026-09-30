package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.block.BlockHeaderValidationException;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.*;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Testnet4ConsensusTest {
    private final NetworkParameters parameters = NetworkParametersRegistry.testnet4();
    private final Map<Hash256, BlockIndex> indexes = new HashMap<>();

    // Synthetic ancestry: no expensive public-network PoW is required for proposal checks.
    private BlockIndex parent(int height) {
        BlockIndex previous = null;
        for (int i = 0; i <= height; i++) {
            var header = new BlockHeader(4, previous == null ? new Hash256(new byte[32]) : previous.hash(),
                    new Hash256(new byte[32]), new UInt32(1_700_000_000L + i * 600L),
                    new UInt32(0x1c3fffc0L), new UInt32(i));
            previous = new BlockIndex(header.hash(), header, i, header.previousBlockHash(), BigInteger.valueOf(i + 1));
            indexes.put(previous.hash(), previous);
        }
        return previous;
    }

    private BlockHeader candidate(BlockIndex parent, long time, NetworkParameters params) {
        var timestamp = new UInt32(time);
        return new BlockHeader(4, parent.hash(), new Hash256(new byte[32]), timestamp,
                ChainHeaderValidator.nextBits(parent, indexes::get, params, timestamp), new UInt32(0));
    }

    @Test void beforeBoundary() { timewarpBoundOnlyAppliesToFirstBlockOfPeriod(2014); }
    @Test void atBoundary() { timewarpBoundOnlyAppliesToFirstBlockOfPeriod(2015); }
    @Test void afterBoundary() { timewarpBoundOnlyAppliesToFirstBlockOfPeriod(2016); }

    private void timewarpBoundOnlyAppliesToFirstBlockOfPeriod(int parentHeight) {
        var parent = parent(parentHeight);
        long time = parent.header().timestamp().value();
        var early = candidate(parent, time - 601, parameters);
        if (parentHeight == 2015) {
            assertThrows(BlockHeaderValidationException.class, () -> ChainHeaderValidator.validateWithoutProofOfWork(
                    early, parent, indexes::get, parameters, () -> time));
            assertThrows(BlockHeaderValidationException.class, () -> new HeaderProcessor(indexes::get, parameters, () -> time).process(early));
        } else {
            assertDoesNotThrow(() -> ChainHeaderValidator.validateWithoutProofOfWork(
                    early, parent, indexes::get, parameters, () -> time));
        }
        assertDoesNotThrow(() -> ChainHeaderValidator.validateWithoutProofOfWork(
                candidate(parent, time - 600, parameters), parent, indexes::get, parameters, () -> time));
    }

    @Test void otherNetworksDoNotEnforceTimewarpBound() {
        var parent = parent(2015);
        for (var params : List.of(NetworkParametersRegistry.mainnet(), NetworkParametersRegistry.testnet())) {
            var header = candidate(parent, parent.header().timestamp().value() - 601, params);
            assertDoesNotThrow(() -> ChainHeaderValidator.validateWithoutProofOfWork(
                    header, parent, indexes::get, params, () -> 1_800_000_000L));
        }
    }

    @Test void medianTimePastStillApplies() {
        var parent = parent(2016);
        var header = candidate(parent, MedianTimePast.calculate(parent, indexes::get), parameters);
        assertThrows(BlockHeaderValidationException.class, () -> ChainHeaderValidator.validateWithoutProofOfWork(
                header, parent, indexes::get, parameters, () -> 1_800_000_000L));
        assertEquals(parent.header().timestamp().value() + 1,
                ChainHeaderValidator.minimumTimestamp(parent, parent.header().timestamp().value(), parameters));
    }

    @Test void retargetUsesFirstBitsEvenWhenLastBlockHasMinimumDifficulty() {
        var parent = parent(2015);
        var old = parent.header();
        var header = new BlockHeader(4, old.previousBlockHash(), old.merkleRoot(),
                new UInt32(1_700_000_000L + parameters.targetTimespanSeconds()), new UInt32(0x1d00ffffL), new UInt32(0));
        parent = new BlockIndex(header.hash(), header, 2015, header.previousBlockHash(), parent.chainWork());
        assertEquals(0x1c3fffc0L, ChainHeaderValidator.nextBits(parent, indexes::get, parameters,
                new UInt32(header.timestamp().value() + 1201)).value());
    }

    @Test void minimumDifficultyExceptionIsStrictAndRecoversPreviousDifficulty() {
        var parent = parent(2016);
        long time = parent.header().timestamp().value();
        assertEquals(0x1c3fffc0L, candidate(parent, time + 1200, parameters).bits().value());
        var minimum = candidate(parent, time + 1201, parameters);
        assertEquals(0x1d00ffffL, minimum.bits().value());
        var child = BlockIndexFactory.createChild(parent, minimum);
        indexes.put(child.hash(), child);
        assertEquals(0x1c3fffc0L, candidate(child, time + 1202, parameters).bits().value());
    }

    @Test void taprootActiveFromFirstBlock() {
        var genesis = parent(0);
        assertEquals(ru.bitcoin.node.consensus.deployment.DeploymentState.ACTIVE,
                TaprootDeployment.stateForNextBlock(genesis, indexes::get, parameters));
        assertTrue(TaprootDeployment.activeFor(BlockIndexFactory.createChild(genesis,
                candidate(genesis, genesis.header().timestamp().value() + 600, parameters)), indexes::get, parameters));
    }
}
