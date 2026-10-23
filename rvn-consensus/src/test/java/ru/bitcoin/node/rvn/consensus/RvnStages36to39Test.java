package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.rvn.protocol.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.consensus.RvnStage13Fixtures.*;

class RvnStages36to39Test {
    private static final RvnHeaderFormatPolicy FORMAT = new RvnHeaderFormatPolicy(0);

    @Test
    void snapshotDoesNotChangeWhenBestTipChanges() {
        var index = index(10);
        index.admitBatchAtomic(List.of(item(1, id(90), 1)), TEST_ONLY, FORMAT);
        var snapshot = index.activeHeadersSnapshot();
        assertEquals(1, snapshot.size());
        byte[] first = snapshot.get(0).raw();
        first[0] ^= 127;
        assertNotEquals(first[0], snapshot.get(0).raw()[0]);
        index.admitBatchAtomic(List.of(item(2, id(1), 2)), TEST_ONLY, FORMAT);
        assertEquals(1, snapshot.size());
        assertEquals(2, index.activeHeadersSnapshot().size());
    }

    @Test
    void replyAfterForkNeverContainsSideBranch() throws Exception {
        var index = index(10);
        index.admitBatchAtomic(List.of(item(1, id(90), 1), item(2, id(1), 2)), TEST_ONLY, FORMAT);
        index.admitBatchAtomic(List.of(item(3, id(1), 2), item(4, id(3), 3)), TEST_ONLY, FORMAT);
        var req = RvnGetHeaders.encode(70028, List.of(id(90).wire()), new byte[32]);
        var response = RvnHeaders.decode(RvnVerifiedHeadersResponder.reply(index, id(90), req, 0), 0);
        assertEquals(3, response.size());
        assertArrayEquals(id(3).wire(), response.get(2).previousHashWire());
    }

    @Test
    void requestAndResponsePaginationSemantics() throws Exception {
        var index = index(10);
        byte[] req = RvnVerifiedHeadersPage.request(index, id(90), 70028, 10);
        var decoded = RvnGetHeaders.decode(req);
        assertEquals(1, decoded.locators().size());
        assertArrayEquals(id(90).wire(), decoded.locators().get(0));
        assertArrayEquals(new byte[32], decoded.stopHash());
        assertFalse(RvnVerifiedHeadersPage.mayHaveMore(RvnHeaders.encode(List.of()), 0));
        index.admitBatchAtomic(List.of(item(1, id(90), 1)), TEST_ONLY, FORMAT);
        assertEquals(2, RvnGetHeaders.decode(RvnVerifiedHeadersPage.request(index, id(90), 70028, 10)).locators().size());
        byte[] response = RvnVerifiedHeadersResponder.reply(index, id(90), req, 0);
        assertEquals(1, RvnHeaders.decode(response, 0).size());
        assertFalse(RvnVerifiedHeadersPage.mayHaveMore(response, 0));
    }
}
