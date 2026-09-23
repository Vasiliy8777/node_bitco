package ru.bitcoin.node.stratum.job;

import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicLong;

public final class ExtraNonceManager {
    public static final int EXTRANONCE1_SIZE = 8;
    public static final int EXTRANONCE2_SIZE = 8;
    private final AtomicLong next = new AtomicLong(new SecureRandom().nextLong());

    public String next() {
        return HexFormat.of().formatHex(ByteBuffer.allocate(8).putLong(next.getAndIncrement()).array());
    }
}
