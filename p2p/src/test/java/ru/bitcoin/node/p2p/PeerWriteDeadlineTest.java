package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.*;
import java.net.Socket;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class PeerWriteDeadlineTest {
    private static final BitcoinMessage MESSAGE = new BitcoinMessage("ping", new byte[8]);

    @Test
    void asyncQueueRejectsExcessAndCloseReleasesQueuedAndActiveReservations() throws Exception {
        var budget = new PeerWriteBudget(2, 2, 560, 560);
        var socket = new ControlledSocket(true);
        var failed = new CountDownLatch(1);
        try (var connection = connection(socket, 5000, budget)) {
            assertTimeoutPreemptively(java.time.Duration.ofSeconds(1),
                    () -> connection.sendAsync(MESSAGE, ignored -> failed.countDown()));
            assertTrue(socket.entered.await(2, TimeUnit.SECONDS));
            connection.sendAsync(MESSAGE, ignored -> { });
            assertThrows(IOException.class, () -> connection.sendAsync(MESSAGE, ignored -> { }));
            assertThrows(IOException.class, () -> connection.send(MESSAGE));
            connection.close();
            assertTrue(failed.await(2, TimeUnit.SECONDS));
            try (var restored = budget.newAccount().reserve(8);
                 var second = budget.newAccount().reserve(8)) {
                assertNotNull(restored);
                assertNotNull(second);
            }
        }
    }

    @Test
    void asyncWriteReportsTimeoutAndReleasesBudgetBeforeCallback() throws Exception {
        var budget = new PeerWriteBudget(1, 1, 280, 280);
        var socket = new ControlledSocket(true);
        var result = new CompletableFuture<Boolean>();
        try (var connection = connection(socket, 100, budget)) {
            connection.sendAsync(MESSAGE, failure -> {
                try (var restored = budget.newAccount().reserve(8)) {
                    result.complete(socket.isClosed());
                } catch (Exception e) { result.completeExceptionally(e); }
            });
            assertTrue(result.get(2, TimeUnit.SECONDS));
        }
    }

    @Test
    void asyncResponsesRemainFifoWhileFirstWriteIsBlocked() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var sent = new CountDownLatch(3);
        var bytes = new ByteArrayOutputStream();
        var failures = new java.util.concurrent.atomic.AtomicReference<IOException>();
        var socket = new ControlledSocket(false) {
            @Override public OutputStream getOutputStream() {
                return new OutputStream() {
                    @Override public void write(int value) { throw new AssertionError("Expected buffered write"); }
                    @Override public void write(byte[] packet, int offset, int length) throws IOException {
                        entered.countDown();
                        try {
                            if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test gate timeout");
                        } catch (InterruptedException e) { throw new InterruptedIOException(); }
                        bytes.write(packet, offset, length);
                        sent.countDown();
                    }
                };
            }
        };
        try (var connection = connection(socket, 5000)) {
            connection.sendAsync(new BitcoinMessage("ping", new byte[]{1}), failures::set);
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            connection.sendAsync(new BitcoinMessage("pong", new byte[]{2}), failures::set);
            connection.sendAsync(new BitcoinMessage("addr", new byte[]{3}), failures::set);
            release.countDown();
            assertTrue(sent.await(2, TimeUnit.SECONDS));
            assertNull(failures.get());
            var reader = new ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader(
                    new ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder(NetworkParametersRegistry.regtest()));
            var input = new ByteArrayInputStream(bytes.toByteArray());
            assertEquals("ping", reader.read(input).orElseThrow().command());
            assertEquals("pong", reader.read(input).orElseThrow().command());
            assertEquals("addr", reader.read(input).orElseThrow().command());
        } finally {
            release.countDown();
        }
    }

    @Test
    void blockedWriteConsumesGlobalBudgetUntilItExits() throws Exception {
        var budget = new PeerWriteBudget(2, 2, 280, 280);
        var blockedSocket = new ControlledSocket(true);
        var healthySocket = new ControlledSocket(false);
        try (var blocked = connection(blockedSocket, 5000, budget);
             var healthy = connection(healthySocket, 5000, budget)) {
            var active = sendAsync(blocked);
            assertTrue(blockedSocket.entered.await(2, TimeUnit.SECONDS));
            var failure = assertThrows(IOException.class, () -> healthy.send(MESSAGE));
            assertTrue(failure.getMessage().contains("budget exhausted"));
            assertEquals(1, healthySocket.entered.getCount(), "Rejected write must not reach socket");
            blocked.close();
            assertInstanceOf(IOException.class, active.get(2, TimeUnit.SECONDS));
            healthy.send(MESSAGE);
            healthy.send(MESSAGE); // Successful writes must release the entire reservation as well.
        }
    }

    @Test
    void interruptedWaitReleasesItsTransportReservation() throws Exception {
        var budget = new PeerWriteBudget(1, 1, 280, 280);
        try (var connection = connection(new ControlledSocket(false), 5000, budget)) {
            Thread.currentThread().interrupt();
            try {
                assertThrows(InterruptedIOException.class, () -> connection.send(MESSAGE));
                assertTrue(Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }
            connection.send(MESSAGE);
        }
    }

    @Test
    void deadlineClosesBlockedSocketWithoutBlockingOtherConnections() throws Exception {
        var socket = new ControlledSocket(true);
        try (var connection = connection(socket, 200);
             var healthy = connection(new ControlledSocket(false), 1000)) {
            var result = sendAsync(connection);
            assertTrue(socket.entered.await(2, TimeUnit.SECONDS));
            healthy.send(MESSAGE);
            assertInstanceOf(IOException.class, result.get(2, TimeUnit.SECONDS));
            assertTrue(socket.isClosed());
            assertFalse(connection.isConnected());
            assertTrue(healthy.isConnected());
        }
    }

    @Test
    void completedWriteCancelsDeadline() throws Exception {
        var socket = new ControlledSocket(false);
        try (var connection = connection(socket, 100)) {
            connection.send(MESSAGE);
            assertFalse(socket.closed.await(300, TimeUnit.MILLISECONDS));
            connection.send(MESSAGE);
            assertTrue(connection.isConnected());
        }
    }

    @Test
    void waitingSenderCanBeInterruptedWithoutClosingActiveWriter() throws Exception {
        var socket = new ControlledSocket(true);
        try (var connection = connection(socket, 5000)) {
            var active = sendAsync(connection);
            assertTrue(socket.entered.await(2, TimeUnit.SECONDS));
            var interrupted = new CompletableFuture<Boolean>();
            Thread waiter = new Thread(() -> {
                Thread.currentThread().interrupt();
                try {
                    connection.send(MESSAGE);
                    interrupted.complete(false);
                } catch (InterruptedIOException expected) {
                    interrupted.complete(Thread.currentThread().isInterrupted());
                } catch (Exception unexpected) {
                    interrupted.completeExceptionally(unexpected);
                }
            });
            waiter.start();
            assertTrue(interrupted.get(2, TimeUnit.SECONDS));
            assertFalse(socket.isClosed());
            connection.close();
            assertInstanceOf(IOException.class, active.get(2, TimeUnit.SECONDS));
        }
    }

    private static CompletableFuture<Exception> sendAsync(PeerConnection connection) {
        return CompletableFuture.supplyAsync(() -> {
            try { connection.send(MESSAGE); return null; }
            catch (Exception failure) { return failure; }
        });
    }

    private static PeerConnection connection(Socket socket, int timeout) throws IOException {
        return connection(socket, timeout, PeerWriteBudget.SHARED);
    }

    private static PeerConnection connection(Socket socket, int timeout, PeerWriteBudget budget) throws IOException {
        var connection = new PeerConnection(NetworkParametersRegistry.regtest(), 1000, 1000, timeout, budget);
        connection.accept(socket);
        return connection;
    }

    /** Models a socket write released only by close, without relying on OS buffer sizes. */
    private static class ControlledSocket extends Socket {
        private final boolean block;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        private ControlledSocket(boolean block) { this.block = block; }
        @Override public boolean isConnected() { return true; }
        @Override public boolean isClosed() { return closed.getCount() == 0; }
        @Override public void setSoTimeout(int timeout) { }
        @Override public void setTcpNoDelay(boolean value) { }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public OutputStream getOutputStream() {
            return new OutputStream() {
                @Override public void write(int value) throws IOException {
                    entered.countDown();
                    if (block) {
                        try {
                            if (!closed.await(10, TimeUnit.SECONDS)) throw new IOException("Test writer stuck");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new InterruptedIOException();
                        }
                    }
                    if (isClosed()) throw new IOException("Socket closed");
                }
            };
        }
        @Override public void close() { closed.countDown(); }
    }
}
