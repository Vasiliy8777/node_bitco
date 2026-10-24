package ru.bitcoin.node.rvn.app.mining;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.support.GenericApplicationContext;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class RvnMiningServiceTest {
    private static ApplicationReadyEvent ready() {
        return new ApplicationReadyEvent(new SpringApplication(), new String[0], new GenericApplicationContext(), Duration.ZERO);
    }
    private static RvnSoloMining.Backend waiting(CountDownLatch entered, boolean fail) {
        return new RvnSoloMining.Backend() {
            public Optional<RvnSoloMining.Job> next() throws IOException {
                entered.countDown();
                if (fail) throw new IOException("validator unavailable");
                return Optional.empty();
            }
            public boolean current(RvnSoloMining.Job job) { fail("No job should be issued"); return false; }
            public Optional<RvnSoloMining.Accepted> accept(RvnSoloMining.Job job, ru.bitcoin.node.rvn.chain.RvnBlock block) {
                fail("No candidate should be admitted"); return Optional.empty();
            }
            public void relay(RvnSoloMining.Accepted block) { fail("No candidate should be relayed"); }
        };
    }
    @Test void selfTestsMustFinishBeforeBackendRunsAndWaitingDoesNotTouchGpu() throws Exception {
        var entered = new CountDownLatch(1); var closed = new AtomicBoolean();
        var miner = new RvnSoloMining(waiting(entered, false), (height, hash, nonce, count, target) -> {
            fail("GPU must stay idle without a validated job"); return null;
        }, () -> 0, 64);
        var service = new RvnMiningService(miner, () -> closed.set(true));
        try {
            service.start();
            assertFalse(entered.await(100, TimeUnit.MILLISECONDS));
            service.onApplicationEvent(ready());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(service.isRunning());
            assertTrue(service.getPhase() > Integer.MAX_VALUE - 100);
        } finally { service.stop(); }
        assertTrue(closed.get()); assertFalse(service.isRunning());
    }
    @Test void validatorFailureStopsServiceAndClosesGpu() throws Exception {
        var entered = new CountDownLatch(1); var closed = new CountDownLatch(1);
        var miner = new RvnSoloMining(waiting(entered, true), (height, hash, nonce, count, target) -> null, () -> 0, 64);
        var service = new RvnMiningService(miner, closed::countDown);
        try {
            service.onApplicationEvent(ready()); service.start();
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(closed.await(2, TimeUnit.SECONDS));
            assertFalse(service.isRunning());
        } finally { service.stop(); }
    }
}
