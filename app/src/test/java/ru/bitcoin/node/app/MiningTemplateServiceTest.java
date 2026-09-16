package ru.bitcoin.node.app;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.mining.NonceMiner;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;
class MiningTemplateServiceTest {
    @TempDir Path directory;
    @Test void freshTemplateTracksAcceptedTipAndUsesNetworkDifficulty() {
        var params=NetworkParametersRegistry.regtest();
        try(var db=new RocksDbDatabase(directory)) {
            var service=new NodeValidationService(db,params,()->1_800_000_000L,new Mempool());
            var first=service.createMiningTemplate(new byte[]{0x51},new byte[0],4_000_000,new FeeRate(0));
            assertEquals(service.activeTip().hash(),first.header().previousBlockHash());
            assertEquals(service.activeTip().header().bits(),first.header().bits());
            assertEquals(1_800_000_000L,first.header().timestamp().value());
            var mined=NonceMiner.search(first,params,0,100_000,()->false).orElseThrow();
            assertEquals(BlockProcessingResult.CONNECTED,service.processBlock(mined));
            var second=service.createMiningTemplate(new byte[]{0x51},new byte[0],4_000_000,new FeeRate(0));
            assertEquals(mined.header().hash(),second.header().previousBlockHash());
            assertNotEquals(first.transactions().getFirst().txId(),second.transactions().getFirst().txId());
            assertEquals(1,service.activeTip().height());
        }
    }
}
