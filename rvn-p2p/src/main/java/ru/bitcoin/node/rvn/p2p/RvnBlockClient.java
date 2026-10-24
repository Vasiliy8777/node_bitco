package ru.bitcoin.node.rvn.p2p;

import ru.bitcoin.node.rvn.protocol.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One bounded witness-block request for an already locally checked header. */
public final class RvnBlockClient {
    private RvnBlockClient() {}
    public record Expected(byte[] id, RvnBlockHeader header) {
        public Expected { if (id == null || id.length != 32 || header == null) throw new IllegalArgumentException("RVN expected block"); id = id.clone(); }
        @Override public byte[] id() { return id.clone(); }
    }
    @FunctionalInterface public interface Receiver { void accept(Expected expected, byte[] payload) throws IOException; }

    /** Bounded in-flight requests hide network latency. Responses may arrive in any
     * order and are consumed immediately, not accumulated as a full batch in RAM. */
    public static void requestBatch(RvnPeerSession session, List<Expected> supplied, long timeoutMs, Receiver receiver)
            throws IOException, InterruptedException {
        requestBatch(session, supplied, timeoutMs, 4, receiver);
    }

    public static void requestBatch(RvnPeerSession session, List<Expected> supplied, long timeoutMs,
                                    int inFlight, Receiver receiver) throws IOException, InterruptedException {
        var expected = List.copyOf(supplied);
        if (expected.isEmpty() || expected.size() > 128 || timeoutMs < 1 || timeoutMs > 120000 || inFlight < 1 || inFlight > 16)
            throw new IllegalArgumentException("RVN block batch parameters");
        var all = new HashSet<String>();
        for (var item : expected) if (!all.add(HexFormat.of().formatHex(item.header().serialize())))
            throw new IllegalArgumentException("Repeated RVN expected header");
        record Pending(Expected expected, long deadline) {}
        var pending = new LinkedHashMap<String, Pending>(); int sent = 0, received = 0, ignored = 0;
        while (received < expected.size()) {
            while (sent < expected.size() && pending.size() < inFlight) {
                var item = expected.get(sent++);
                var inventory = new ByteArrayOutputStream();
                RvnCompactSize.write(inventory, 1); RvnWire.writeU32(inventory, 0x40000002L); inventory.writeBytes(item.id());
                session.send(new RvnMessage("getdata", inventory.toByteArray()));
                pending.put(HexFormat.of().formatHex(item.header().serialize()),
                        new Pending(item, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)));
            }
            long remaining = pending.values().stream().mapToLong(Pending::deadline).min().orElseThrow() - System.nanoTime();
            if (remaining <= 0) throw new IOException("Timed out waiting for pipelined RVN block");
            var message = session.poll(Math.max(1, Math.min(1000, TimeUnit.NANOSECONDS.toMillis(remaining))));
            if (message == null) continue;
            if (message.command().equals("notfound")) throw new IOException("RVN peer cannot provide requested batch");
            if (message.command().equals("block")) {
                byte[] payload = message.payload();
                if (payload.length > 8_000_000) throw new IOException("RVN block size limit");
                var actual = RvnBlockHeader.read(new ByteArrayInputStream(payload), RvnActivation.MAINNET_KAWPOW_TIME);
                var matched = pending.remove(HexFormat.of().formatHex(actual.serialize()));
                if (matched == null) throw new IOException("Unrequested or duplicate RVN block header");
                long processingStart = System.nanoTime();
                receiver.accept(matched.expected(), payload);
                long processingNanos = System.nanoTime() - processingStart;
                // Signature/body checks and synchronous archive writes are our
                // work. They must not consume other requests' network deadlines.
                pending.replaceAll((key, value) -> new Pending(value.expected(), value.deadline() + processingNanos));
                received++;
            } else if (++ignored > 64) throw new IOException("Too many unrelated RVN batch messages");
        }
    }
    public static byte[] request(RvnPeerSession session, byte[] blockIdWire, RvnBlockHeader expected, long timeoutMs)
            throws IOException, InterruptedException {
        if (blockIdWire == null || blockIdWire.length != 32 || expected == null || timeoutMs < 1 || timeoutMs > 120000)
            throw new IllegalArgumentException("RVN block request parameters");
        var inventory = new ByteArrayOutputStream();
        RvnCompactSize.write(inventory, 1); RvnWire.writeU32(inventory, 0x40000002L); inventory.writeBytes(blockIdWire);
        session.send(new RvnMessage("getdata", inventory.toByteArray()));
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        int ignored = 0;
        while (true) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IOException("Timed out waiting for RVN block");
            var message = session.poll(Math.max(1, Math.min(1000, TimeUnit.NANOSECONDS.toMillis(remaining))));
            if (message == null) continue;
            if (message.command().equals("notfound")) throw new IOException("RVN peer cannot provide requested block");
            if (message.command().equals("block")) {
                byte[] payload = message.payload();
                var actual = RvnBlockHeader.read(new ByteArrayInputStream(payload), RvnActivation.MAINNET_KAWPOW_TIME);
                if (!Arrays.equals(actual.serialize(), expected.serialize()))
                    throw new IOException("RVN block does not match requested validated header");
                return payload;
            }
            if (++ignored > 64) throw new IOException("Too many unrelated RVN block messages");
        }
    }
}
