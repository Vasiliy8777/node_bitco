package ru.bitcoin.node.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.bitcoin.node.app.service.GpuMinerProcess;
import ru.bitcoin.node.stratum.StratumServer;
import java.nio.file.Path;

@Configuration
@ConditionalOnProperty(name = "bitcoin.gpu-miner.enabled", havingValue = "true")
public class GpuMinerConfiguration {
    @Bean(destroyMethod = "close")
    GpuMinerProcess gpuMinerProcess(StratumServer server, StratumConfiguration.Credentials credentials,
            @Value("${bitcoin.gpu-miner.python:python}") String python,
            @Value("${bitcoin.gpu-miner.script:tools/gpu-miner/miner.py}") String script,
            @Value("${bitcoin.gpu-miner.device:0}") int device,
            @Value("${bitcoin.stratum.user:miner}") String user) {
        return new GpuMinerProcess(python, Path.of(script), device, server.port(), user + ".gpu01", credentials.password());
    }
}
