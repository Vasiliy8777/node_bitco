package ru.bitcoin.node.chain;

import ru.bitcoin.node.protocol.network.BitcoinNetwork;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import ru.bitcoin.node.common.types.Hash256;

/** BIP9 Speedy Trial, evaluated on the candidate branch, not the active tip. */
public final class TaprootDeployment {
    private static final long START = 1619222400L;
    private static final long TIMEOUT = 1628640000L;
    private static final int PERIOD = 2016;
    private static final Map<BlockIndexLookup, Map<BitcoinNetwork, LinkedHashMap<Hash256, Integer>>> CACHE = new WeakHashMap<>();
    private TaprootDeployment() { }

    public static synchronized boolean activeFor(BlockIndex candidate, BlockIndexLookup lookup, NetworkParameters parameters) {
        if (parameters.network() == BitcoinNetwork.REGTEST || parameters.network() == BitcoinNetwork.SIGNET) return true;
        if (candidate.height() == 0 || candidate.header().timestamp().value() < START) return false;
        BlockIndex parent = required(lookup, candidate.previousBlockHash());
        long boundaryHeight = parent.height() - (parent.height() + 1) % PERIOD;
        if (boundaryHeight < 0) return false;
        while (parent.height() > boundaryHeight) parent = required(lookup, parent.previousBlockHash());
        var cache = CACHE.computeIfAbsent(lookup, key -> new EnumMap<>(BitcoinNetwork.class))
                .computeIfAbsent(parameters.network(), key -> new LinkedHashMap<>());
        var periods = new ArrayList<BlockIndex>();
        int state = 0; // DEFINED, STARTED, LOCKED_IN, ACTIVE, FAILED
        while (true) {
            Integer cached = cache.get(parent.hash());
            if (cached != null) { state = cached; break; }
            if (MedianTimePast.calculate(parent, lookup) < START) break;
            periods.add(parent);
            if (parent.height() < PERIOD) break;
            for (int i = 0; i < PERIOD; i++) parent = required(lookup, parent.previousBlockHash());
        }
        long minHeight = parameters.network() == BitcoinNetwork.MAINNET ? 709632 : 0;
        for (int i = periods.size() - 1; i >= 0; i--) {
            BlockIndex boundary = periods.get(i);
            long mtp = MedianTimePast.calculate(boundary, lookup);
            if (state == 0) state = mtp >= TIMEOUT ? 4 : mtp >= START ? 1 : 0;
            else if (state == 1) {
                if (mtp >= TIMEOUT) {
                    state = 4;
                } else {
                int count = 0;
                BlockIndex current = boundary;
                for (int j = 0; j < PERIOD; j++) {
                    int version = current.header().version();
                    if ((version & 0xe0000000) == 0x20000000 && (version & 4) != 0) count++;
                    if (j + 1 < PERIOD) current = required(lookup, current.previousBlockHash());
                }
                int threshold = parameters.network() == BitcoinNetwork.MAINNET ? 1815 : 1512;
                if (count >= threshold) state = 2;
                }
            } else if (state == 2 && boundary.height() + 1 >= minHeight) state = 3;
            cache.put(boundary.hash(), state);
            if (cache.size() > 1024) cache.pollFirstEntry();
        }
        return state == 3;
    }

    private static BlockIndex required(BlockIndexLookup lookup, ru.bitcoin.node.common.types.Hash256 hash) {
        var index = lookup.find(hash);
        if (index == null) throw new IllegalStateException("Missing ancestor for Taproot activation");
        return index;
    }
}
