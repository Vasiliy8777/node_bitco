package ru.bitcoin.node.app.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeLifecycleSpringAdapterTest {

    @Test
    void shouldStartNodeAutomaticallyWhenContextStarts()
            throws Exception {

        TestNodeLifecycle lifecycle =
                new TestNodeLifecycle();

        try (AnnotationConfigApplicationContext context =
                     context(
                             lifecycle,
                             true
                     )) {

            assertTrue(
                    lifecycle.started.await(
                            1,
                            TimeUnit.SECONDS
                    )
            );

            assertTrue(
                    context
                            .getBean(
                                    NodeLifecycleSpringAdapter.class
                            )
                            .isRunning()
            );
        }

        assertTrue(
                lifecycle.closed.get()
        );
    }

    @Test
    void shouldNotCreateAutoStartAdapterWhenDisabled() {

        TestNodeLifecycle lifecycle =
                new TestNodeLifecycle();

        try (AnnotationConfigApplicationContext context =
                     context(
                             lifecycle,
                             false
                     )) {

            assertTrue(
                    context.getBeansOfType(
                            NodeLifecycleSpringAdapter.class
                    ).isEmpty()
            );

            assertEquals(
                    1L,
                    lifecycle.started.getCount()
            );
        }
    }

    private static AnnotationConfigApplicationContext context(
            TestNodeLifecycle lifecycle,
            boolean autoStart
    ) {

        AnnotationConfigApplicationContext context =
                new AnnotationConfigApplicationContext();

        context
                .getEnvironment()
                .getPropertySources()
                .addFirst(
                        new MapPropertySource(
                                "test",
                                Map.of(
                                        "bitcoin.node.auto-start",
                                        Boolean.toString(
                                                autoStart
                                        )
                                )
                        )
                );

        NodeLifecycleRunner runner =
                new NodeLifecycleRunner(
                        lifecycle
                );

        context
                .getBeanFactory()
                .registerSingleton(
                        "nodeLifecycleRunner",
                        runner
                );

        context.register(
                TestConfiguration.class
        );

        context.refresh();

        return context;
    }

    @Configuration
    static class TestConfiguration {

        @Bean
        @ConditionalOnProperty(
                name = "bitcoin.node.auto-start",
                havingValue = "true"
        )
        NodeLifecycleSpringAdapter nodeLifecycleSpringAdapter(
                NodeLifecycleRunner runner
        ) {
            return new NodeLifecycleSpringAdapter(
                    runner
            );
        }
    }

    private static final class TestNodeLifecycle
            implements NodeLifecycle {

        private final CountDownLatch started =
                new CountDownLatch(1);

        private final CountDownLatch release =
                new CountDownLatch(1);

        private final AtomicBoolean closed =
                new AtomicBoolean();

        @Override
        public void start()
                throws IOException {

            started.countDown();

            try {

                release.await(
                        5,
                        TimeUnit.SECONDS
                );

            } catch (InterruptedException exception) {

                Thread.currentThread()
                        .interrupt();

                throw new IOException(
                        "Interrupted",
                        exception
                );
            }
        }

        @Override
        public NodeLifecycleState state() {

            return started.getCount() == 0
                    ? NodeLifecycleState.STARTING
                    : NodeLifecycleState.NEW;
        }

        @Override
        public boolean isRunning() {
            return false;
        }

        @Override
        public void close() {

            closed.set(
                    true
            );

            release.countDown();
        }
    }
}