package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.rvn.protocol.*;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.rvn.consensus.RvnStage13Fixtures.*;

class RvnVerifiedHeadersResponderTest {
    static final RvnHeaderFormatPolicy FORMAT = new RvnHeaderFormatPolicy(0);

    static byte[] request(RvnHash256 locator, RvnHash256 stop) {
        return RvnGetHeaders.encode(70028, List.of(locator.wire()), stop.wire());
    }

    @Test
    void activeChainOnlyAndStopInclusive() throws Exception {
        var index = index(10);
        index.admitBatchAtomic(List.of(item(1, id(90), 1), item(2, id(1), 2)), TEST_ONLY, FORMAT);
        index.admitBatchAtomic(List.of(item(3, id(1), 2)), TEST_ONLY, FORMAT);
        var reply = RvnVerifiedHeadersResponder.reply(index, id(90), request(id(90), id(2)), 0);
        var headers = RvnHeaders.decode(reply, 0);
        assertEquals(2, headers.size());
        assertArrayEquals(id(90).wire(), headers.get(0).previousHashWire());
        assertArrayEquals(id(1).wire(), headers.get(1).previousHashWire());
        assertEquals(1, RvnHeaders.decode(RvnVerifiedHeadersResponder.reply(index, id(90),
                request(id(1), id(2)), 0), 0).size());
        assertTrue(RvnHeaders.decode(RvnVerifiedHeadersResponder.reply(index, id(90),
                request(id(44), id(2)), 0), 0).isEmpty());
        assertTrue(RvnHeaders.decode(RvnVerifiedHeadersResponder.reply(index, id(90),
                request(id(2), id(90)), 0), 0).isEmpty());
    }
}
