package ru.bitcoin.node.p2p.sync;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.p2p.sync.HeadersSyncTimeoutPolicy.*;

class HeadersSyncTimeoutPolicyTest {
    @Test void scalesMicrosecondsBeforeDividingAndAllowsFutureBestHeader() {
        assertEquals(BASE_MICROS + 1, initialDeadline(0, 1, 0, 600));
        assertEquals(BASE_MICROS - 1, initialDeadline(0, 0, 1, 600));
        assertEquals(BASE_MICROS + 144000, initialDeadline(0, 86400, 0, 600));
        assertEquals(123 + BASE_MICROS + 1000000, initialDeadline(123, 600000, 0, 600));
    }
    @Test void onlySyncPeerNeedsAnotherPreferredPeerAndStrictDeadline() {
        var state = new State(); state.start(0, 1_000_000, 0, 600);
        long end = state.deadlineMicros();
        assertEquals(Action.NONE, state.evaluate(end, 1_000_000, 0, 1, 2, true, false));
        assertEquals(Action.NONE, state.evaluate(end + 1, 1_000_000, 0, 2, 2, true, false));
        assertEquals(Action.NONE, state.evaluate(end + 1, 1_000_000, 0, 1, 1, true, false));
        assertEquals(Action.NONE, state.evaluate(end + 1, 1_000_000, 0, 1, 0, false, false));
        assertEquals(Action.DISCONNECT, state.evaluate(end + 1, 1_000_000, 0, 1, 2, true, false));
        assertEquals(Action.DISCONNECT, state.evaluate(end + 1, 1_000_000, 0, 1, 1, false, false));
    }
    @Test void nobanResetsWithoutDisconnectAndCatchupPermanentlyDisablesTimer() {
        var state = new State(); state.start(0, 1_000_000, 0, 600);
        assertEquals(Action.RESET_NOBAN, state.evaluate(state.deadlineMicros()+1, 1_000_000, 0, 1, 2, true, true));
        assertFalse(state.started()); assertEquals(0, state.deadlineMicros());
        state.start(0, 1_000_000, 0, 600);
        assertEquals(Action.NONE, state.evaluate(Long.MAX_VALUE-1, 1_000_000, 1_000_000-RECENT_SECONDS+1, 1, 2, true, false));
        assertEquals(Long.MAX_VALUE, state.deadlineMicros());
        assertEquals(Action.NONE, state.evaluate(Long.MAX_VALUE, 2_000_000, 0, 1, 2, true, false));
    }
    @Test void recentBoundaryAndSourcePreferenceMatchCore() {
        long now = 1_000_000, old = now - RECENT_SECONDS;
        assertFalse(mayStart(true,false,true,false,2,false,1,now,old));
        assertTrue(mayStart(true,false,true,false,2,false,1,now,old+1));
        assertTrue(mayStart(true,false,false,false,1,false,0,now,old));
        assertFalse(mayStart(true,false,false,false,1,true,0,now,old));
        assertTrue(mayStart(true,false,false,false,0,true,0,now,old));
        assertFalse(mayStart(false,false,true,false,1,false,0,now,now));
        assertFalse(mayStart(true,true,true,false,1,false,0,now,now));
        assertTrue(preferred(false,false,false,true)); assertTrue(preferred(true,true,false,true));
        assertFalse(preferred(true,false,false,true)); assertFalse(preferred(false,true,true,true));
        assertFalse(preferred(false,false,false,false));
    }
}
