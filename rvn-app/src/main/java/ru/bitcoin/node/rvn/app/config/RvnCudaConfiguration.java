package ru.bitcoin.node.rvn.app.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import ru.bitcoin.node.rvn.pow.*;
import java.io.IOException;
import java.nio.file.Path;

/** Explicit GPU diagnostic/worker lifecycle, independent of production mining. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "ravencoin.mining.cuda.enabled", havingValue = "true")
public class RvnCudaConfiguration {
    @Bean(destroyMethod = "close")
    RvnCudaKawpowWorker rvnCudaWorker(RvnDataDirectories directories,
            @Value("${ravencoin.mining.cuda.python:python}") String python,
            @Value("${ravencoin.mining.cuda.worker:rvn-pow/native/cuda/worker.py}") String worker,
            @Value("${ravencoin.mining.cuda.device:0}") int device,
            @Value("${ravencoin.mining.cuda.reserve-mib:1024}") long reserveMiB) throws IOException {
        if (reserveMiB < 256 || reserveMiB > 65536) throw new IllegalArgumentException("CUDA reserve must be 256..65536 MiB");
        return new RvnCudaKawpowWorker(python, Path.of(worker), directories.temporary(), device, reserveMiB * 1024 * 1024);
    }

    @Bean
    ApplicationRunner rvnCudaSelfTest(RvnCudaKawpowWorker worker, RvnMiningSettings mining,
            @Value("${ravencoin.mining.cuda.job-self-test-enabled:false}") boolean jobSelfTest) {
        return args -> {
            RvnCudaKawpowSelfTest.run(worker);
            if (jobSelfTest) {
                ru.bitcoin.node.rvn.app.mining.RvnCudaMiningRoundSelfTest.run(worker, mining.requirePayout());
                System.out.println("RVN CUDA local job self-test OK: template, GPU search, independent CPU/native validation; no mainnet block submitted");
            }
            System.out.println("RVN CUDA KawPoW verified against Core vectors; mining waits for complete historical validation and synchronization");
        };
    }
}
