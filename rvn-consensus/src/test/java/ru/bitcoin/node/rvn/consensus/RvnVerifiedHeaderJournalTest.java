package ru.bitcoin.node.rvn.consensus;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.math.BigInteger;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class RvnVerifiedHeaderJournalTest {
    @TempDir
    Path temp;
    static final long BITS = 0x207fffffL;

    static RvnHash256 id(int n) {
        byte[] b = new byte[32];
        b[0] = (byte) n;
        return new RvnHash256(b);
    }

    static byte[] header(RvnHash256 parent, int height) {
        byte[] b = new byte[120];
        System.arraycopy(parent.wire(), 0, b, 4, 32);
        for (int i = 0; i < 4; i++) {
            b[72 + i] = (byte) (BITS >>> (8 * i));
            b[76 + i] = (byte) (height >>> (8 * i));
        }
        return b;
    }

    static RvnValidatedHeaderIndex index() {
        return new RvnValidatedHeaderIndex(10, id(90), 0, BigInteger.ZERO, CompactTarget.decode(BITS));
    }

    @Test
    void roundTripAndFailClosedReplay() throws Exception {
        Path path = temp.resolve("headers.dat");
        var r1 = new RvnVerifiedHeaderJournal.Record(id(1), header(id(90), 1));
        var r2 = new RvnVerifiedHeaderJournal.Record(id(2), header(id(1), 2));
        RvnVerifiedHeaderJournal.writeAtomically(path, List.of(r1, r2));
        var restored = RvnVerifiedHeaderJournal.read(path);
        assertEquals(2, restored.size());
        assertArrayEquals(r1.raw(), restored.get(0).raw());
        assertThrows(SecurityException.class, () -> RvnVerifiedHeaderJournal.replay(
                path, index(), new RvnHeaderFormatPolicy(0), RvnHeaderProofGate.unavailable()));
        var accepted = index();
        var testOnly = new RvnHeaderProofGate.Verifier() {
            public boolean blockIdMatches(RvnHeaderCandidate c, byte[] raw) {
                return true;
            }

            public boolean validProofOfWork(RvnHeaderCandidate c, byte[] raw) {
                return true;
            }

            public boolean validContext(RvnHeaderCandidate c) {
                return true;
            }
        };
        assertEquals(2, RvnVerifiedHeaderJournal.replay(path, accepted,
                new RvnHeaderFormatPolicy(0), testOnly));
        assertEquals(2, accepted.size());
    }

    @Test
    void corruptedOrTruncatedJournalRejected() throws Exception {
        Path path = temp.resolve("headers.dat");
        RvnVerifiedHeaderJournal.writeAtomically(path, List.of(
                new RvnVerifiedHeaderJournal.Record(id(1), header(id(90), 1))));
        byte[] original = Files.readAllBytes(path);
        byte[] corrupt = original.clone();
        corrupt[50] ^= 1;
        Files.write(path, corrupt);
        assertThrows(java.io.IOException.class, () -> RvnVerifiedHeaderJournal.read(path));
        Files.write(path, Arrays.copyOf(original, original.length - 3));
        assertThrows(java.io.IOException.class, () -> RvnVerifiedHeaderJournal.read(path));
        Files.write(path, original);
        assertEquals(1, RvnVerifiedHeaderJournal.read(path).size());
    }

    @Test
    void defensiveCopyAndNoTrailingBytes() throws Exception {
        Path path = temp.resolve("headers.dat");
        var r = new RvnVerifiedHeaderJournal.Record(id(1), header(id(90), 1));
        byte[] a = r.raw();
        a[4] = 99;
        assertEquals(90, r.raw()[4]);
        RvnVerifiedHeaderJournal.writeAtomically(path, List.of(r));
        Files.write(path, new byte[]{1}, StandardOpenOption.APPEND);
        assertThrows(java.io.IOException.class, () -> RvnVerifiedHeaderJournal.read(path));
    }
}
