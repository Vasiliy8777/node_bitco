package ru.bitcoin.node.app.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import java.nio.file.Path;
import java.time.Instant;

/** A data directory explicitly enables persistent chain and mempool validation. */
@Configuration
@ConditionalOnProperty(name = "bitcoin.data-directory")
public class NodeConfiguration {
    @Bean(destroyMethod = "close")
    public RocksDbDatabase chainDatabase(@Value("${bitcoin.data-directory}") String path) {
        return new RocksDbDatabase(Path.of(path));
    }
    @Bean
    public NodeValidationService nodeValidationService(RocksDbDatabase database, NetworkParameters parameters) {
        return new NodeValidationService(database, parameters, () -> Instant.now().getEpochSecond(), new Mempool());
    }
}
