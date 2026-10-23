package ru.bitcoin.node.rvn.p2p;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.rvn.protocol.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RvnStage7Test {
    @TempDir Path temp;
    @Test void roundRobinAndBackoff() {
        var pool = new RvnPeerPool(3);
        var a = new RvnPeerEndpoint("localhost", 1001);
        var b = new RvnPeerEndpoint("localhost", 1002);
        pool.add(a);pool.add(b);
        var selector = new RvnPeerSelector(pool, new RvnPeerBackoff(100, 1000));
        assertEquals(a, selector.next(0).orElseThrow());
        assertEquals(b, selector.next(0).orElseThrow());
        selector.failure(a, 0);
        assertEquals(b, selector.next(0).orElseThrow());
        assertEquals(b, selector.next(0).orElseThrow());
        assertEquals(a, selector.next(100_000_000).orElseThrow());
    }
    @Test void emptyResponseIsNotPersisted() throws Exception {
        var spool = new RvnUnverifiedHeaderSpool(temp, RvnActivation.MAINNET_KAWPOW_TIME, 2);
        var ingestor = new RvnHeadersIngestor(spool, RvnActivation.MAINNET_KAWPOW_TIME);
        var receipt = ingestor.accept(List.of());
        assertFalse(receipt.stored());
        assertNull(receipt.contentId());
        assertEquals(0, spool.list().size());
        assertEquals(1, ingestor.metrics().emptyResponses());
    }
    @Test void malformedLocatorRejectedBeforePeerSelection() throws Exception {
        var pool = new RvnPeerPool(1);
        pool.add(new RvnPeerEndpoint("127.0.0.1", 12345));
        var selector = new RvnPeerSelector(pool, new RvnPeerBackoff(100, 1000));
        var ingestor = new RvnHeadersIngestor(new RvnUnverifiedHeaderSpool(temp, 0, 3), 0);
        var coordinator = new RvnHeadersProbeCoordinator(selector, ingestor, RvnNetwork.REGTEST, 500, 16);
        assertThrows(IllegalArgumentException.class, () -> coordinator.fetchOnce(70028,
                List.of(new byte[3]), new byte[32], 0, 1000));
    }
}
