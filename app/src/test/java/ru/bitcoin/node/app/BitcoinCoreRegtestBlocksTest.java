package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class BitcoinCoreRegtestBlocksTest {
    @TempDir Path directory;
    @Test void importsCoreGeneratedBlocksAndMatchesCoinbaseUtxos() throws Exception {
        List<OutPoint> outputs = new ArrayList<>();
        String[] manifest;
        try(var in = Objects.requireNonNull(getClass().getResourceAsStream("/core-regtest/blocks.txt"))) {
            manifest = new String(in.readAllBytes(),StandardCharsets.UTF_8).strip().split("\\R");
        }
        assertEquals(3,manifest.length);
        try(var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db,NetworkParametersRegistry.regtest(),()->1_800_000_000L,new Mempool());
            for(String line:manifest) {
                var fields=line.split(" ");
                int height=Integer.parseInt(fields[0]);
                try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/core-regtest/"+height+".bin"))) {
                    var block=BlockParser.parse(in.readAllBytes());
                    assertEquals(fields[1],block.header().hash().toDisplayHex());
                    assertEquals(BlockProcessingResult.CONNECTED,service.processBlock(block));
                    assertEquals(height,service.activeTip().height());
                    assertEquals(1,block.transactions().size());
                    var tx=block.transactions().getFirst();
                    assertTrue(tx.outputs().size() >= 1);
                    var point=new OutPoint(tx.txId(),new UInt32(0));
                    outputs.add(point);
                    var coin=new RocksDbUtxoStore(db).find(point).orElseThrow();
                    assertEquals(5_000_000_000L,coin.amount());
                    assertEquals(height,coin.height());
                    assertTrue(coin.coinbase());
                    assertArrayEquals(new byte[]{0x51},coin.scriptPubKey());
                }
            }
        }
        try(var db=new RocksDbDatabase(directory)) {
            var service=new NodeValidationService(db,NetworkParametersRegistry.regtest(),()->1_800_000_000L,new Mempool());
            assertEquals(3,service.activeTip().height());
            assertEquals(manifest[2].split(" ")[1],service.activeTip().hash().toDisplayHex());
            for(var point:outputs) assertEquals(5_000_000_000L,new RocksDbUtxoStore(db).find(point).orElseThrow().amount());
        }
    }
}
