package ru.bitcoin.node.stratum.job;

import java.nio.ByteBuffer;
import java.util.HexFormat;
import ru.bitcoin.node.common.ExtraNonceSequence;

public final class ExtraNonceManager {
    public static final int EXTRANONCE1_SIZE = 8;
    public static final int EXTRANONCE2_SIZE = 8;
    private final ExtraNonceSequence next = new ExtraNonceSequence();

    public String next() {
        return HexFormat.of().formatHex(ByteBuffer.allocate(8).putLong(next.next()).array());
    }
}
