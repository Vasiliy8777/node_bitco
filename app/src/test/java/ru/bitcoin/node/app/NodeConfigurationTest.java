package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import ru.bitcoin.node.app.config.*;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class NodeConfigurationTest {
    @TempDir Path directory;
    @Test void explicitDataDirectoryInitializesServiceAndReleasesDatabaseOnClose() {
        for(int i=0;i<2;i++) {
            try(var context = new AnnotationConfigApplicationContext()) {
                context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("node-test", Map.of(
                        "bitcoin.data-directory",directory.toString(),"bitcoin.network","regtest")));
                context.register(NetworkConfiguration.class,NodeConfiguration.class);
                context.refresh();
                assertEquals(0,context.getBean(NodeValidationService.class).activeTip().height());
            }
        }
    }
}
