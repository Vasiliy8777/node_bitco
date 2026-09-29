package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;

import java.nio.file.Path;

@SpringBootTest(properties = {
        "spring.profiles.active=regtest", "bitcoin.network=regtest", "bitcoin.node.auto-start=false",
        "bitcoin.p2p.listen=false", "bitcoin.rpc.enabled=false", "bitcoin.stratum.enabled=false"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class BitcoinNodeApplicationTests {

    @TempDir static Path directory;

    @DynamicPropertySource
    static void isolatedDatabase(DynamicPropertyRegistry properties) {
        properties.add("bitcoin.data-directory", directory::toString);
    }

    @Test
    void contextLoads() {
    }
}
