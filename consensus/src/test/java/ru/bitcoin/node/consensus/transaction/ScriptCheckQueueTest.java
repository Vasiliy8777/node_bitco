package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ScriptCheckQueueTest {
    @Test
    void waitsForAllChecks() {
        AtomicInteger n = new AtomicInteger();
        ScriptCheckQueue.run(List.of(n::incrementAndGet, n::incrementAndGet, n::incrementAndGet));
        assertEquals(3, n.get());
    }

    @Test
    void propagatesFailure() {
        assertThrows(IllegalStateException.class, () -> ScriptCheckQueue.run(List.of(() -> {
        }, () -> {
            throw new IllegalStateException("bad");
        })));
    }
}
