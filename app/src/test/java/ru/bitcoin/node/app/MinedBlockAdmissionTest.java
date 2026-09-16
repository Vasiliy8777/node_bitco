package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.mining.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MinedBlockAdmissionTest {
    @TempDir Path directory;
    @Test void locallyBuiltAndMinedBlockIsAcceptedAndPersisted() throws Exception {
        try(var db=new RocksDbDatabase(directory)) {
            var parameters=NetworkParametersRegistry.regtest();
            var service=new NodeValidationService(db,parameters,()->1_800_000_000L,new Mempool());
            var parent=service.activeTip();
            var coins=new RocksDbUtxoStore(db);
            var candidate=BlockTemplateBuilder.build(parent,h->h.equals(parent.hash())?parent:null,coins,
                    parameters,4,new UInt32(parent.header().timestamp().value()+1),parent.header().bits(),
                    new byte[]{0x51},new byte[]{1,2,3},List.of());
            assertEquals(0,coins.count());
            var block=NonceMiner.search(candidate,parameters,0,100_000,()->false).orElseThrow();
            assertEquals(BlockProcessingResult.CONNECTED,service.processBlock(block));
            assertEquals(block.header().hash(),service.activeTip().hash());
            var point=new OutPoint(block.transactions().getFirst().txId(),new UInt32(0));
            assertEquals(5_000_000_000L,coins.find(point).orElseThrow().amount());
            assertEquals(1,coins.count());
            // Deterministic artifact for submitting the exact same block to an isolated Core.
            Files.createDirectories(Path.of("target"));
            Files.writeString(Path.of("target","locally-mined-regtest.hex"),HexFormat.of().formatHex(BlockSerializer.serialize(block)));
            Files.writeString(Path.of("target","locally-mined-regtest.hash"),block.header().hash().toDisplayHex());
        }
        try(var db=new RocksDbDatabase(directory)) {
            var service=new NodeValidationService(db,NetworkParametersRegistry.regtest(),()->1_800_000_000L,new Mempool());
            assertEquals(1,service.activeTip().height());
            assertEquals(1,new RocksDbUtxoStore(db).count());
        }
    }
}
