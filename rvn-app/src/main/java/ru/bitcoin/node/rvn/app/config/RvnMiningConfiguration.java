package ru.bitcoin.node.rvn.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.bitcoin.node.rvn.app.chain.*;
import ru.bitcoin.node.rvn.app.mining.*;
import ru.bitcoin.node.rvn.app.sync.RvnHeaderSyncService;
import ru.bitcoin.node.rvn.pow.RvnCudaKawpowWorker;
import ru.bitcoin.node.rvn.storage.*;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ravencoin.mining.enabled", havingValue = "true")
public class RvnMiningConfiguration {
    @Bean
    RvnMiningService rvnMiningService(RvnDataDirectories directories, RvnCudaKawpowWorker worker, RvnHeaderStore headers, RvnCoinStore coins,
            RvnMainnetHeaderChain chain, RvnNativeBlockReplay replay, RvnHeaderSyncService sync,
            RvnMiningSettings settings,
            @Value("${ravencoin.validation.canonical-asset-replay-enabled:false}") boolean assets,
            @Value("${ravencoin.mining.batch-size:4096}") int batchSize) {
        if (!assets) throw new IllegalArgumentException("RVN mining requires historical asset replay");
        var backend = new RvnEmptyBlockMiningBackend(headers, coins, chain, replay, settings.requirePayout(),
                replay::completeHistoricalValidation, sync::miningPeerReady,
                () -> System.currentTimeMillis() / 1000, sync::publishMinedBlock);
        var miner = new RvnSoloMining(backend, worker::search, () -> System.currentTimeMillis() / 1000, batchSize);
        return new RvnMiningService(miner, worker);
    }
}
