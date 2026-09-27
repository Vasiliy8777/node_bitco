package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.deployment.Deployment;
import ru.bitcoin.node.consensus.deployment.DeploymentManager;
import ru.bitcoin.node.consensus.deployment.DeploymentState;
import ru.bitcoin.node.protocol.network.BitcoinNetwork;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.util.EnumMap;
import java.util.Map;
import java.util.WeakHashMap;

/** BIP341 Taproot Speedy Trial, evaluated on the candidate branch. */
public final class TaprootDeployment {
    private static final long START = 1619222400L;
    private static final long TIMEOUT = 1628640000L;
    private static final int PERIOD = 2016;
    private static final Map<BlockIndexLookup, Map<BitcoinNetwork, DeploymentManager<BlockIndex, Hash256>>> MANAGERS =
            new WeakHashMap<>();

    private TaprootDeployment() { }

    public static synchronized boolean activeFor(
            BlockIndex candidate,
            BlockIndexLookup lookup,
            NetworkParameters parameters
    ) {
        if (candidate == null || lookup == null || parameters == null)
            throw new IllegalArgumentException("candidate, lookup and parameters must not be null");
        if (parameters.network() == BitcoinNetwork.REGTEST || parameters.network() == BitcoinNetwork.SIGNET) return true;
        if (candidate.height() == 0) return false;
        BlockIndex parent = required(lookup, candidate.previousBlockHash());
        return manager(lookup, parameters).stateForNextBlock(parent) == DeploymentState.ACTIVE;
    }

    static synchronized DeploymentState stateFor(
            BlockIndex candidate,
            BlockIndexLookup lookup,
            NetworkParameters parameters
    ) {
        if (parameters.network() == BitcoinNetwork.REGTEST || parameters.network() == BitcoinNetwork.SIGNET)
            return DeploymentState.ACTIVE;
        if (candidate.height() == 0) return DeploymentState.DEFINED;
        return manager(lookup, parameters).stateForNextBlock(required(lookup, candidate.previousBlockHash()));
    }

    private static DeploymentManager<BlockIndex, Hash256> manager(
            BlockIndexLookup lookup,
            NetworkParameters parameters
    ) {
        return MANAGERS.computeIfAbsent(lookup, ignored -> new EnumMap<>(BitcoinNetwork.class))
                .computeIfAbsent(parameters.network(), ignored -> new DeploymentManager<>(deployment(parameters), access(lookup)));
    }

    private static Deployment deployment(NetworkParameters parameters) {
        int threshold = parameters.network() == BitcoinNetwork.MAINNET ? 1815 : 1512;
        long minHeight = parameters.network() == BitcoinNetwork.MAINNET ? 709632L : 0L;
        return new Deployment("taproot", 2, START, TIMEOUT, threshold, PERIOD,
                minHeight, Deployment.TransitionPolicy.SPEEDY_TRIAL);
    }

    private static DeploymentManager.ChainAccess<BlockIndex, Hash256> access(BlockIndexLookup lookup) {
        return new DeploymentManager.ChainAccess<>() {
            @Override public long height(BlockIndex block) { return block.height(); }
            @Override public Hash256 key(BlockIndex block) { return block.hash(); }
            @Override public BlockIndex parent(BlockIndex block) {
                if (block.height() == 0) return null;
                return required(lookup, block.previousBlockHash());
            }
            @Override public long medianTimePast(BlockIndex block) { return MedianTimePast.calculate(block, lookup); }
            @Override public int version(BlockIndex block) { return block.header().version(); }
        };
    }

    private static BlockIndex required(BlockIndexLookup lookup, Hash256 hash) {
        BlockIndex index = lookup.find(hash);
        if (index == null) throw new IllegalStateException("Missing ancestor for Taproot activation: " + hash.toDisplayHex());
        return index;
    }
}
