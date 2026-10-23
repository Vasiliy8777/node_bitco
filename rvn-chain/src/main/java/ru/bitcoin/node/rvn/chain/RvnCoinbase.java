package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.*;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Coinbase assembly for future local templates. Does not start mining or construct genesis. */
public final class RvnCoinbase {
    private RvnCoinbase() {}
    public static long subsidy(long height) {
        if (height < 0 || height > 0xffff_ffffL) throw new IllegalArgumentException("RVN block height");
        long halvings = height / 2_100_000L;
        return halvings >= 64 ? 0 : (5000 * RvnBlockBodyChecks.COIN) >>> (int) halvings;
    }
    public static Transaction create(long height, long extraNonce, long fees, RvnPayoutAddress payout) {
        if (height < 1 || extraNonce < 0 || fees < 0 || fees > RvnBlockBodyChecks.MAX_MONEY)
            throw new IllegalArgumentException("RVN coinbase height/extraNonce/fees");
        Objects.requireNonNull(payout);
        long value = Math.addExact(subsidy(height), fees);
        if (value > RvnBlockBodyChecks.MAX_MONEY) throw new IllegalArgumentException("RVN reward exceeds money range");
        var script = new ByteArrayOutputStream();
        if (height <= 16) script.write(0x50 + (int) height); else pushNumber(script, height);
        pushNumber(script, extraNonce);
        byte[] tag = "/java-rvn/".getBytes(StandardCharsets.US_ASCII);
        script.write(tag.length); script.writeBytes(tag);
        return new Transaction(1, List.of(new TxIn(OutPoint.coinbase(), script.toByteArray(), TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(value, payout.scriptPubKey())), new UInt32(0));
    }
    private static void pushNumber(ByteArrayOutputStream script, long number) {
        var encoded = new ByteArrayOutputStream();
        for (long value = number; value != 0; value >>>= 8) encoded.write((int) value & 255);
        byte[] bytes = encoded.toByteArray();
        if (bytes.length > 0 && (bytes[bytes.length - 1] & 0x80) != 0) {
            bytes = Arrays.copyOf(bytes, bytes.length + 1);
        }
        script.write(bytes.length); script.writeBytes(bytes);
    }
}
