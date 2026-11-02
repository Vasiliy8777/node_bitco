package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.sync.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class PeerHeaderRequestIntegrationTest {
    @Test void terminalFutureTimeoutIsNotRetriedAsAShortAdmissionWait() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.peer.sendAsync(BitcoinMessages.getHeaders(fixture.request)); fixture.takeRequest();
            var result = download(fixture);
            await(() -> fixture.peer.messageDispatcher().hasPendingHeaders());
            var failure = new HeaderSynchronizationTimeoutException(Duration.ofSeconds(9));
            fixture.peer.messageDispatcher().failAllPending(failure);
            var reported = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertSame(failure, reported.getCause());
            assertFalse(fixture.peer.messageDispatcher().hasPendingHeaders());
        }
    }

    @Test void syncAndAsyncProducersShareOneRequestAndEmptyHeadersReleaseAdmission() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.peer.sendAsync(BitcoinMessages.getHeaders(fixture.request));
            fixture.takeRequest();
            fixture.peer.send(BitcoinMessages.getHeaders(fixture.request));
            assertFalse(fixture.peer.trySendGetHeaders(fixture.request));
            assertNull(fixture.requests.poll(100, TimeUnit.MILLISECONDS));
            fixture.respond(List.of()); fixture.takeResponse();
            assertTrue(fixture.peer.trySendGetHeaders(fixture.request));
            fixture.takeRequest();
        }
    }

    @Test void nonemptyHeadersDoNotReleaseAdmissionBeforeValidation() throws Exception {
        try (var fixture = new Fixture()) {
            assertTrue(fixture.peer.trySendGetHeaders(fixture.request)); fixture.takeRequest();
            fixture.respond(List.of(ru.bitcoin.node.protocol.block.GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header()));
            fixture.takeResponse();
            assertFalse(fixture.peer.trySendGetHeaders(fixture.request));
            fixture.peer.headersResponseValidated();
            assertTrue(fixture.peer.trySendGetHeaders(fixture.request)); fixture.takeRequest();
        }
    }

    @Test void synchronousWaiterCanConsumeTheEarlierAsyncResponseWithoutAnotherWireRequest() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.peer.sendAsync(BitcoinMessages.getHeaders(fixture.request)); fixture.takeRequest();
            var result = download(fixture);
            await(() -> fixture.peer.messageDispatcher().hasPendingHeaders());
            fixture.respond(List.of());
            assertTrue(result.get(3, TimeUnit.SECONDS).isEmpty());
            assertNull(fixture.requests.poll(100, TimeUnit.MILLISECONDS));
        }
    }

    @Test void asyncResponseDispatchedBeforeWaiterRegistrationDoesNotStrandTheNewWaiter() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.peer.sendAsync(BitcoinMessages.getHeaders(fixture.request)); fixture.takeRequest();
            fixture.respond(List.of(ru.bitcoin.node.protocol.block.GenesisBlockFactory.create(NetworkParametersRegistry.regtest()).header()));
            fixture.takeResponse();
            var result = download(fixture);
            await(() -> fixture.peer.messageDispatcher().hasPendingHeaders());
            assertNull(fixture.requests.poll(100, TimeUnit.MILLISECONDS));
            fixture.peer.headersResponseValidated();
            fixture.takeRequest();
            fixture.respond(List.of());
            assertTrue(result.get(3, TimeUnit.SECONDS).isEmpty());
        }
    }

    @Test void waitingForAdmissionDoesNotExtendTheOverallDownloadTimeout() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.peer.sendAsync(BitcoinMessages.getHeaders(fixture.request)); fixture.takeRequest();
            assertTimeoutPreemptively(Duration.ofSeconds(2), () -> assertThrows(HeaderSynchronizationTimeoutException.class,
                    () -> new HeaderSynchronizer(fixture.peer, Duration.ofMillis(150))
                            .download(fixture.request.locatorHashes(), fixture.request.stopHash())));
            assertFalse(fixture.peer.messageDispatcher().hasPendingHeaders());
            assertFalse(fixture.peer.trySendGetHeaders(fixture.request), "A caller timeout is not a validated peer response");
            assertNull(fixture.requests.poll(100, TimeUnit.MILLISECONDS));
        }
    }

    private static CompletableFuture<HeadersMessage> download(Fixture fixture) {
        return CompletableFuture.supplyAsync(() -> {
            try { return new HeaderSynchronizer(fixture.peer, Duration.ofSeconds(5))
                    .download(fixture.request.locatorHashes(), fixture.request.stopHash()); }
            catch (java.io.IOException failure) { throw new CompletionException(failure); }
        });
    }
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean());
    }
    private static final class Fixture implements AutoCloseable {
        final PeerManager serving = new PeerManager(), clients = new PeerManager();
        final BitcoinServer server = new BitcoinServer(NetworkParametersRegistry.regtest(), serving);
        final AtomicReference<Peer> remote = new AtomicReference<>();
        final BlockingQueue<BitcoinMessage> requests = new LinkedBlockingQueue<>(), responses = new LinkedBlockingQueue<>();
        final GetHeadersMessage request = new GetHeadersMessage(VersionMessage.CURRENT_PROTOCOL_VERSION,
                List.of(NetworkParametersRegistry.regtest().genesisBlockHash()), new Hash256(new byte[32]));
        final Peer peer;
        Fixture() throws Exception {
            serving.addPeerListener(connection -> {
                remote.set(connection);
                connection.addMessageListener((source, message) -> { if ("getheaders".equals(message.command())) requests.add(message); });
            });
            server.start(0, 0);
            peer = new BitcoinClient(NetworkParametersRegistry.regtest()).connectManaged("127.0.0.1", server.localPort(), 0, PeerConnectionRole.FULL_RELAY);
            peer.addMessageListener((source, message) -> { if ("headers".equals(message.command())) responses.add(message); });
            clients.add(peer, PeerConnectionRole.FULL_RELAY);
        }
        void takeRequest() throws Exception { assertNotNull(requests.poll(3, TimeUnit.SECONDS), "Expected GETHEADERS on the wire"); }
        void takeResponse() throws Exception { assertNotNull(responses.poll(3, TimeUnit.SECONDS), "Expected asynchronous HEADERS delivery"); }
        void respond(List<ru.bitcoin.node.protocol.block.BlockHeader> headers) throws Exception {
            remote.get().send(BitcoinMessages.headers(new HeadersMessage(headers)));
        }
        public void close() throws Exception { clients.close(); server.close(); serving.close(); }
    }
}
