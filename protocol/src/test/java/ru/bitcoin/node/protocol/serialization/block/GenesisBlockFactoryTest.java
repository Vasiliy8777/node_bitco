package ru.bitcoin.node.protocol.serialization.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.BitcoinNetwork;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.BlockHeaderSerializer;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;

import static org.junit.jupiter.api.Assertions.*;

class GenesisBlockFactoryTest {
    @Test
    void createsMainnetGenesis() {
        assertGenesis(BitcoinNetwork.MAINNET,
                "000000000019d6689c085ae165831e934ff763ae46a2a6c172b3f1b60a8ce26f", 1231006505L, 2083236893L, 0x1d00ffffL);
    }

    @Test
    void createsTestnet3Genesis() {
        assertGenesis(BitcoinNetwork.TESTNET,
                "000000000933ea01ad0ee984209779baaec3ced90fa3f408719526f8d77f4943", 1296688602L, 414098458L, 0x1d00ffffL);
    }

    @Test
    void createsSignetGenesis() {
        assertGenesis(BitcoinNetwork.SIGNET,
                "00000008819873e925422c1ff0f99f7cc9bbb232af63a077a480a3633bee1ef6", 1598918400L, 52613770L, 0x1e0377aeL);
    }

    @Test
    void createsRegtestGenesis() {
        assertGenesis(BitcoinNetwork.REGTEST,
                "0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206", 1296688602L, 2L, 0x207fffffL);
    }

    @Test
    void serializesExactMainnetHeader() {
        var block = GenesisBlockFactory.create(NetworkParametersRegistry.mainnet());
        assertArrayEquals(HexUtils.decode("01000000" + "00".repeat(32)
                + "3ba3edfd7a7b12b27ac72c3e67768f617fc81bc3888a51323a9fb8aa4b1e5e4a"
                + "29ab5f49ffff001d1dac2b7c"), BlockHeaderSerializer.serialize(block.header()));
        assertEquals(285, BlockSerializer.serialize(block).length);
    }

    private static void assertGenesis(BitcoinNetwork network, String hash, long time, long nonce, long bits) {
        var block = GenesisBlockFactory.create(NetworkParametersRegistry.forNetwork(network));
        assertEquals(hash, block.hash().toDisplayHex());
        assertEquals(1, block.header().version());
        assertEquals(time, block.header().timestamp().value());
        assertEquals(nonce, block.header().nonce().value());
        assertEquals(bits, block.header().bits().value());
        assertEquals("00".repeat(32), block.header().previousBlockHash().toDisplayHex());
        assertEquals("4a5e1e4baab89f3a32518a88c31bc87f618f76673e2cc77ab2127b7afdeda33b",
                block.header().merkleRoot().toDisplayHex());
        assertEquals(1, block.transactions().size());
        var tx = block.transactions().getFirst();
        assertTrue(tx.isCoinbase());
        assertEquals(block.header().merkleRoot(), tx.txId());
        assertEquals(5_000_000_000L, tx.outputs().getFirst().value());
        assertArrayEquals(BlockSerializer.serialize(block),
                BlockSerializer.serialize(BlockParser.parse(BlockSerializer.serialize(block))));
    }
}
