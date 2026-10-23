package ru.bitcoin.node.rvn.p2p;

import ru.bitcoin.node.rvn.protocol.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** One bounded witness-block request for an already locally checked header. */
public final class RvnBlockClient {
    private RvnBlockClient() {}
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
