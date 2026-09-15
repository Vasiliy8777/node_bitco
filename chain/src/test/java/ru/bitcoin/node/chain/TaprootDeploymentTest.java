package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.*;
import java.math.BigInteger;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaprootDeploymentTest {
    private static final long START = 1619222400L;
    private static final long TIMEOUT = 1628640000L;

    @Test void testnetThresholdAndLockInDelay() {
        var chain = chain(4 * 2016, 1512, START, 0);
        BlockIndexLookup lookup = chain.byHash::get;
        var params = NetworkParametersRegistry.testnet();
        assertFalse(TaprootDeployment.activeFor(chain.blocks.get(2016), lookup, params));
        assertFalse(TaprootDeployment.activeFor(chain.blocks.get(4032), lookup, params));
        assertFalse(TaprootDeployment.activeFor(chain.blocks.get(6047), lookup, params));
        assertTrue(TaprootDeployment.activeFor(chain.blocks.get(6048), lookup, params));
        assertTrue(TaprootDeployment.activeFor(chain.blocks.get(8064), lookup, params));
    }
    @Test void insufficientSignalsNeverLockIn() {
        var chain = chain(4 * 2016, 1511, START, 0);
        assertFalse(TaprootDeployment.activeFor(chain.blocks.getLast(), chain.byHash::get, NetworkParametersRegistry.testnet()));
    }
    @Test void timeoutWinsOverSignals() {
        var chain = chain(4 * 2016, 2016, TIMEOUT, 0);
        assertFalse(TaprootDeployment.activeFor(chain.blocks.getLast(), chain.byHash::get, NetworkParametersRegistry.testnet()));
    }
    @Test void mainnetWaitsForMinimumHeight() {
        // An artificial but linked branch near the activation height; no PoW is needed to test state transitions.
        int offset = 346 * 2016;
        var chain = chain(7 * 2016, 1815, START, offset);
        BlockIndexLookup lookup = chain.byHash::get;
        assertFalse(TaprootDeployment.activeFor(chain.blocks.get(5 * 2016), lookup, NetworkParametersRegistry.mainnet()));
        assertTrue(TaprootDeployment.activeFor(chain.blocks.get(6 * 2016), lookup, NetworkParametersRegistry.mainnet()));
    }
    @Test void signetAndRegtestAreAlwaysActive() {
        var chain = chain(1, 0, 1, 0);
        for (var params : List.of(NetworkParametersRegistry.signet(), NetworkParametersRegistry.regtest())) {
            assertTrue(TaprootDeployment.activeFor(chain.blocks.getLast(), chain.byHash::get, params));
        }
    }
    private record Chain(List<BlockIndex> blocks, Map<Hash256, BlockIndex> byHash) { }
    private static Chain chain(int count, int signals, long time, int offset) {
        var blocks = new ArrayList<BlockIndex>();
        var map = new HashMap<Hash256, BlockIndex>();
        Hash256 previous = new Hash256(new byte[32]);
        // Prefix one complete pre-start period when the artificial branch starts above genesis.
        int prefix = offset == 0 ? 0 : 2016;
        for (int i = -prefix; i <= count; i++) {
            int height = offset + i;
            long timestamp = i < 0 ? START - 100 : time;
            int version = i % 2016 >= 0 && i % 2016 < signals ? 0x20000004 : 0x20000000;
            var header = new BlockHeader(version, previous, new Hash256(new byte[32]),
                    new UInt32(timestamp), new UInt32(0x207fffffL), new UInt32(height));
            var index = new BlockIndex(header.hash(), header, height, previous, BigInteger.valueOf(height + 1));
            map.put(index.hash(), index); if (i >= 0) blocks.add(index); previous = index.hash();
        }
        return new Chain(blocks, map);
    }
}
