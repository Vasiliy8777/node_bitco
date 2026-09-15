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

class BitcoinCoreReorgTest {
    private void compareAllCoins(RocksDbDatabase db) throws Exception {
        String[] rows;
        try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/core-reorg/utxos.txt"))) {
            rows=new String(in.readAllBytes(),StandardCharsets.UTF_8).strip().split("\\R");
        }
        var store=new RocksDbUtxoStore(db);
        assertEquals(103,rows.length);
        assertEquals(rows.length,store.count(),"No extra or missing UTXOs");
        var unique=new HashSet<OutPoint>();
        for(String row:rows) {
            var fields=row.split("\\|");
            var point=new OutPoint(Hash256.fromDisplayHex(fields[0]),new UInt32(Long.parseLong(fields[1])));
            assertTrue(unique.add(point));
            var coin=store.find(point).orElseThrow();
            assertEquals(Long.parseLong(fields[2]),coin.amount());
            assertEquals(Long.parseLong(fields[3]),coin.height());
            assertArrayEquals(HexFormat.of().parseHex(fields[4]),coin.scriptPubKey());
            assertTrue(coin.coinbase());
        }
    }
    @TempDir Path directory;
    private byte[] resource(String name) throws Exception {
        try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/core-spend/"+name))) {return in.readAllBytes();}
    }
    @Test void longerCoreBranchRestoresSpentCoinAndRemovesDisconnectedOutputs() throws Exception {
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
            for(int height=102;height<=103;height++) {
                byte[] raw;
                try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/core-reorg/"+height+".bin"))) {
                    raw=in.readAllBytes();
                }
                var alternative=BlockParser.parse(raw);
                assertEquals(height==102 ? BlockProcessingResult.STORED_SIDE_CHAIN_CONTEXT_PENDING : BlockProcessingResult.CONNECTED,
                        service.processBlock(alternative));
            }
            assertEquals(103,service.activeTip().height());
            assertEquals(5_000_000_000L,coins.find(spent).orElseThrow().amount());
            assertTrue(coins.find(created).isEmpty());
            var detached=BlockParser.parse(resource("102.bin"));
            var detachedCoinbase=new OutPoint(detached.transactions().getFirst().txId(),new UInt32(0));
            assertTrue(coins.find(detachedCoinbase).isEmpty());
            compareAllCoins(db);
        }
        try(var db=new RocksDbDatabase(directory)) {
            var service=new NodeValidationService(db,NetworkParametersRegistry.regtest(),()->1_800_000_000L,new Mempool());
            assertEquals(103,service.activeTip().height());
                        try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/core-reorg/103.bin"))) {
                assertEquals(BlockParser.parse(in.readAllBytes()).header().hash(),service.activeTip().hash());
            }
            assertEquals(5_000_000_000L,new RocksDbUtxoStore(db).find(spent).orElseThrow().amount());
            assertTrue(new RocksDbUtxoStore(db).find(created).isEmpty());
            compareAllCoins(db);
        }
    }
}
