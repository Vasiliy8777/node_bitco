package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.p2p.message.*;
import ru.bitcoin.node.p2p.sync.BlockNotFoundException;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class PeerMessageDispatcherTest {

    @Test
    void shouldRouteBlocksByHashRegardlessOfArrivalOrder()
            throws Exception {

        Block block1 =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.mainnet()
                );

        Block block2 =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.regtest()
                );

        assertNotEquals(
                block1.hash(),
                block2.hash()
        );

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<Block> future1 =
                dispatcher.registerBlock(
                        block1.hash()
                );

        CompletableFuture<Block> future2 =
                dispatcher.registerBlock(
                        block2.hash()
                );

        /*
         * B2 arrives before B1.
         */
        dispatcher.dispatch(
                BitcoinMessages.block(
                        new BlockMessage(
                                block2
                        )
                )
        );

        assertFalse(
                future1.isDone()
        );

        assertTrue(
                future2.isDone()
        );

        assertEquals(
                block2.hash(),
                future2.join().hash()
        );

        /*
         * B1 arrives second.
         */
        dispatcher.dispatch(
                BitcoinMessages.block(
                        new BlockMessage(
                                block1
                        )
                )
        );

        assertTrue(
                future1.isDone()
        );

        assertEquals(
                block1.hash(),
                future1.join().hash()
        );
    }

    @Test
    void shouldRejectDuplicatePendingBlockRegistration() {

        Block block =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.mainnet()
                );

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<Block> original =
                dispatcher.registerBlock(
                        block.hash()
                );

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> dispatcher.registerBlock(
                                block.hash()
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains(
                                block.hash()
                                        .toDisplayHex()
                        )
        );

        /*
         * Duplicate registration must not replace the
         * original pending request.
         */
        assertFalse(
                original.isDone()
        );
    }

    @Test
    void shouldNotCompleteDifferentPendingRequestForUnsolicitedBlock() {

        Block expected =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.mainnet()
                );

        Block unsolicited =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.regtest()
                );

        assertNotEquals(
                expected.hash(),
                unsolicited.hash()
        );

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<Block> expectedFuture =
                dispatcher.registerBlock(
                        expected.hash()
                );

        /*
         * There is no READY peer in this unit test.
         *
         * An unsolicited block falls through to
         * Peer.handleMessage(), which therefore rejects it.
         *
         * What this test verifies is the routing invariant:
         * an unrelated block must never complete the pending
         * request for another hash.
         */
        assertThrows(
                IllegalStateException.class,
                () -> dispatcher.dispatch(
                        BitcoinMessages.block(
                                new BlockMessage(
                                        unsolicited
                                )
                        )
                )
        );

        assertFalse(
                expectedFuture.isDone()
        );
    }

    @Test
    void shouldFailOnlyMatchingPendingBlockForNotFound()
            throws Exception {

        Block block1 =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.mainnet()
                );

        Block block2 =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.regtest()
                );

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<Block> future1 =
                dispatcher.registerBlock(
                        block1.hash()
                );

        CompletableFuture<Block> future2 =
                dispatcher.registerBlock(
                        block2.hash()
                );

        dispatcher.dispatch(
                BitcoinMessages.notFound(
                        new NotFoundMessage(
                                List.of(
                                        new InventoryVector(
                                                InventoryVector.MSG_WITNESS_BLOCK,
                                                block2.hash()
                                        )
                                )
                        )
                )
        );

        assertFalse(
                future1.isDone()
        );

        assertTrue(
                future2.isCompletedExceptionally()
        );

        CompletionException exception =
                assertThrows(
                        CompletionException.class,
                        future2::join
                );

        assertInstanceOf(
                BlockNotFoundException.class,
                exception.getCause()
        );

        BlockNotFoundException notFound =
                (BlockNotFoundException)
                        exception.getCause();

        assertEquals(
                block2.hash(),
                notFound.blockHash()
        );
    }

    @Test
    void shouldIgnoreNonBlockNotFoundForPendingBlock()
            throws Exception {

        Block block =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.mainnet()
                );

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<Block> future =
                dispatcher.registerBlock(
                        block.hash()
                );

        assertThrows(
                IllegalStateException.class,
                () -> dispatcher.dispatch(
                        BitcoinMessages.notFound(
                                new NotFoundMessage(
                                        List.of(
                                                new InventoryVector(
                                                        InventoryVector.MSG_TX,
                                                        block.hash()
                                                )
                                        )
                                )
                        )
                )
        );

        assertFalse(
                future.isDone()
        );
    }

    @Test
    void shouldFailMultiplePendingBlocksFromSingleNotFound()
            throws Exception {

        Block block1 =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.mainnet()
                );

        Block block2 =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.regtest()
                );

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<Block> future1 =
                dispatcher.registerBlock(
                        block1.hash()
                );

        CompletableFuture<Block> future2 =
                dispatcher.registerBlock(
                        block2.hash()
                );

        dispatcher.dispatch(
                BitcoinMessages.notFound(
                        new NotFoundMessage(
                                List.of(
                                        new InventoryVector(
                                                InventoryVector.MSG_BLOCK,
                                                block1.hash()
                                        ),
                                        new InventoryVector(
                                                InventoryVector.MSG_WITNESS_BLOCK,
                                                block2.hash()
                                        )
                                )
                        )
                )
        );

        assertTrue(
                future1.isCompletedExceptionally()
        );

        assertTrue(
                future2.isCompletedExceptionally()
        );

        assertInstanceOf(
                BlockNotFoundException.class,
                assertThrows(
                        CompletionException.class,
                        future1::join
                ).getCause()
        );

        assertInstanceOf(
                BlockNotFoundException.class,
                assertThrows(
                        CompletionException.class,
                        future2::join
                ).getCause()
        );
    }

    @Test
    void shouldUnregisterExactPendingBlockRequest() {

        Block block =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.mainnet()
                );

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<Block> future =
                dispatcher.registerBlock(
                        block.hash()
                );

        dispatcher.unregisterBlock(
                block.hash(),
                future
        );

        /*
         * Successful second registration proves that the
         * original pending entry was removed.
         */
        CompletableFuture<Block> replacement =
                dispatcher.registerBlock(
                        block.hash()
                );

        assertNotSame(
                future,
                replacement
        );

        assertFalse(
                replacement.isDone()
        );
    }

    @Test
    void shouldNotUnregisterDifferentFuture() {

        Block block =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.mainnet()
                );

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<Block> registered =
                dispatcher.registerBlock(
                        block.hash()
                );

        CompletableFuture<Block> different =
                new CompletableFuture<>();

        dispatcher.unregisterBlock(
                block.hash(),
                different
        );

        /*
         * Original registration must still exist.
         */
        assertThrows(
                IllegalStateException.class,
                () -> dispatcher.registerBlock(
                        block.hash()
                )
        );

        assertFalse(
                registered.isDone()
        );
    }

    @Test
    void shouldFailAllPendingBlocks() {

        Block firstBlock =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.mainnet()
                );

        Hash256 secondHash =
                Hash256.fromDisplayHex(
                        "0000000000000000000000000000000000000000000000000000000000000001"
                );

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<Block> first =
                dispatcher.registerBlock(
                        firstBlock.hash()
                );

        CompletableFuture<Block> second =
                dispatcher.registerBlock(
                        secondHash
                );

        IOException failure =
                new IOException(
                        "Peer disconnected"
                );

        dispatcher.failAllPendingBlocks(
                failure
        );

        CompletableFuture<Block> replacement =
                dispatcher.registerBlock(
                        firstBlock.hash()
                );

        assertFalse(
                replacement.isDone()
        );

        assertTrue(
                first.isCompletedExceptionally()
        );

        assertTrue(
                second.isCompletedExceptionally()
        );
    }

    @Test
    void shouldRejectDuplicatePendingHeadersRegistration() {

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<HeadersMessage> first =
                dispatcher.registerHeaders();

        assertNotNull(
                first
        );

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        dispatcher::registerHeaders
                );

        assertEquals(
                "Headers request is already pending",
                exception.getMessage()
        );

        assertFalse(
                first.isDone()
        );
    }

    @Test
    void shouldAllowNewHeadersRegistrationAfterUnregister() {

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<HeadersMessage> first =
                dispatcher.registerHeaders();

        dispatcher.unregisterHeaders(
                first
        );

        CompletableFuture<HeadersMessage> second =
                dispatcher.registerHeaders();

        assertNotNull(
                second
        );

        assertNotSame(
                first,
                second
        );

        assertFalse(
                second.isDone()
        );
    }

    @Test
    void shouldNotUnregisterDifferentHeadersFuture() {

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<HeadersMessage> registered =
                dispatcher.registerHeaders();

        CompletableFuture<HeadersMessage> different =
                new CompletableFuture<>();

        dispatcher.unregisterHeaders(
                different
        );

        assertThrows(
                IllegalStateException.class,
                dispatcher::registerHeaders
        );

        assertFalse(
                registered.isDone()
        );

        dispatcher.unregisterHeaders(
                registered
        );
    }

    @Test
    void shouldAllowFailingEmptyPendingBlockRegistry() {

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        assertDoesNotThrow(
                () -> dispatcher.failAllPendingBlocks(
                        new IOException(
                                "Peer disconnected"
                        )
                )
        );
    }

    @Test
    void shouldCompletePendingHeadersRequest()
            throws Exception {

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        HeadersMessage expected =
                new HeadersMessage(
                        List.of()
                );

        CompletableFuture<HeadersMessage> future =
                dispatcher.registerHeaders();

        dispatcher.dispatch(
                BitcoinMessages.headers(
                        expected
                )
        );

        assertTrue(
                future.isDone()
        );

        assertFalse(
                future.isCompletedExceptionally()
        );

        HeadersMessage actual =
                future.join();

        assertTrue(
                actual.isEmpty()
        );
    }

    @Test
    void shouldFailPendingHeaders() {

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<HeadersMessage> future =
                dispatcher.registerHeaders();

        IOException failure =
                new IOException(
                        "Peer disconnected"
                );

        dispatcher.failAllPending(
                failure
        );

        assertTrue(
                future.isCompletedExceptionally()
        );

        CompletionException exception =
                assertThrows(
                        CompletionException.class,
                        future::join
                );

        assertSame(
                failure,
                exception.getCause()
        );

        /*
         * Failure must also remove the old registration.
         */
        CompletableFuture<HeadersMessage> replacement =
                dispatcher.registerHeaders();

        assertFalse(
                replacement.isDone()
        );
    }

    @Test
    void shouldFailBlocksAndHeadersTogether() {

        Block block =
                GenesisBlockFactory.create(
                        NetworkParametersRegistry.mainnet()
                );

        PeerMessageDispatcher dispatcher =
                new PeerMessageDispatcher(
                        peer()
                );

        CompletableFuture<Block> blockFuture =
                dispatcher.registerBlock(
                        block.hash()
                );

        CompletableFuture<HeadersMessage> headersFuture =
                dispatcher.registerHeaders();

        IOException failure =
                new IOException(
                        "Peer disconnected"
                );

        dispatcher.failAllPending(
                failure
        );

        assertTrue(
                blockFuture.isCompletedExceptionally()
        );

        assertTrue(
                headersFuture.isCompletedExceptionally()
        );

        assertSame(
                failure,
                assertThrows(
                        CompletionException.class,
                        blockFuture::join
                ).getCause()
        );

        assertSame(
                failure,
                assertThrows(
                        CompletionException.class,
                        headersFuture::join
                ).getCause()
        );
    }

    private static Peer peer() {

        PeerConnection connection =
                new PeerConnection(
                        NetworkParametersRegistry.mainnet(),
                        5_000,
                        5_000
                );

        return new Peer(
                connection,
                0L,
                0,
                true
        );
    }
}