package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.common.types.*;
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

class BitcoinCoreSpendTest {
    @TempDir Path directory;
    private byte[] resource(String name) throws Exception {
        try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/core-spend/"+name))) {return in.readAllBytes();}
    }
    @Test void importsMatureCoinbaseSpendAndPersistsItsEffects() throws Exception {
        var ids=new String(resource("spend.txt"),StandardCharsets.UTF_8).strip().split("\\R");
        var spent=new OutPoint(Hash256.fromDisplayHex(ids[0]),new UInt32(0));
        var created=new OutPoint(Hash256.fromDisplayHex(ids[1]),new UInt32(0));
        var lines=new String(resource("blocks.txt"),StandardCharsets.UTF_8).strip().split("\\R");
        assertEquals(102,lines.length);
        try(var db=new RocksDbDatabase(directory)) {
            var service=new NodeValidationService(db,NetworkParametersRegistry.regtest(),()->1_800_000_000L,new Mempool());
            var coins=new RocksDbUtxoStore(db);
            for(String line:lines) {
                var fields=line.split(" "); int height=Integer.parseInt(fields[0]);
                var block=BlockParser.parse(resource(height+".bin"));
                assertEquals(fields[1],block.header().hash().toDisplayHex());
                assertEquals(BlockProcessingResult.CONNECTED,service.processBlock(block));
                if(height==101) assertTrue(coins.find(spent).isPresent());
                if(height==102) {
                    assertEquals(2,block.transactions().size());
                    assertEquals(ids[1],block.transactions().get(1).txId().toDisplayHex());
                    assertEquals(5_000_010_000L,block.transactions().getFirst().outputs().getFirst().value());
                }
            }
            assertTrue(coins.find(spent).isEmpty());
            var coin=coins.find(created).orElseThrow();
            assertEquals(4_999_990_000L,coin.amount());
            assertEquals(102,coin.height());
            assertFalse(coin.coinbase());
        }
        try(var db=new RocksDbDatabase(directory)) {
            var service=new NodeValidationService(db,NetworkParametersRegistry.regtest(),()->1_800_000_000L,new Mempool());
            assertEquals(102,service.activeTip().height());
            assertEquals(lines[101].split(" ")[1],service.activeTip().hash().toDisplayHex());
            assertTrue(new RocksDbUtxoStore(db).find(spent).isEmpty());
            assertEquals(4_999_990_000L,new RocksDbUtxoStore(db).find(created).orElseThrow().amount());
        }
    }
}
