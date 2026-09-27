package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.common.types.Hash256;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Wire codec for the BIP157 compact-filter P2P messages. */
public final class CompactFilterMessageCodec {
    public static final int BASIC_FILTER_TYPE = 0;
    public static final int MAX_CFILTERS = 1_000;
    public static final int MAX_CFHEADERS = 2_000;
    public static final int CHECKPOINT_INTERVAL = 1_000;

    private CompactFilterMessageCodec() {}

    public record RangeRequest(int filterType, long startHeight, Hash256 stopHash) {
        public RangeRequest {
            if (filterType < 0 || filterType > 255) throw new IllegalArgumentException("filterType must fit uint8");
            if (startHeight < 0 || startHeight > 0xffff_ffffL) throw new IllegalArgumentException("startHeight must fit uint32");
            Objects.requireNonNull(stopHash, "stopHash");
        }
    }

    public record CheckpointRequest(int filterType, Hash256 stopHash) {
        public CheckpointRequest {
            if (filterType < 0 || filterType > 255) throw new IllegalArgumentException("filterType must fit uint8");
            Objects.requireNonNull(stopHash, "stopHash");
        }
    }

    public static byte[] encodeRangeRequest(RangeRequest value) {
        Objects.requireNonNull(value, "value");
        ByteArrayOutputStream out = new ByteArrayOutputStream(37);
        out.write(value.filterType());
        writeUInt32LE(out, value.startHeight());
        out.writeBytes(value.stopHash().bytes());
        return out.toByteArray();
    }

    public static RangeRequest decodeRangeRequest(byte[] payload) {
        if (payload == null || payload.length != 37) throw new IllegalArgumentException("BIP157 range request must be 37 bytes");
        return new RangeRequest(payload[0] & 0xff, readUInt32LE(payload, 1), new Hash256(slice(payload, 5, 32)));
    }

    public static byte[] encodeCheckpointRequest(CheckpointRequest value) {
        Objects.requireNonNull(value, "value");
        ByteArrayOutputStream out = new ByteArrayOutputStream(33);
        out.write(value.filterType());
        out.writeBytes(value.stopHash().bytes());
        return out.toByteArray();
    }

    public static CheckpointRequest decodeCheckpointRequest(byte[] payload) {
        if (payload == null || payload.length != 33) throw new IllegalArgumentException("BIP157 checkpoint request must be 33 bytes");
        return new CheckpointRequest(payload[0] & 0xff, new Hash256(slice(payload, 1, 32)));
    }

    public static byte[] encodeCFilter(int filterType, Hash256 blockHash, byte[] filter) {
        Objects.requireNonNull(blockHash, "blockHash");
        Objects.requireNonNull(filter, "filter");
        ByteArrayOutputStream out = new ByteArrayOutputStream(33 + 9 + filter.length);
        out.write(filterType);
        out.writeBytes(blockHash.bytes());
        out.writeBytes(CompactSize.encode(filter.length));
        out.writeBytes(filter);
        return out.toByteArray();
    }

    public static byte[] encodeCfHeaders(int filterType, Hash256 stopHash, Hash256 previousHeader,
                                         List<Hash256> filterHashes) {
        Objects.requireNonNull(stopHash, "stopHash");
        Objects.requireNonNull(previousHeader, "previousHeader");
        Objects.requireNonNull(filterHashes, "filterHashes");
        if (filterHashes.size() > MAX_CFHEADERS) throw new IllegalArgumentException("Too many filter hashes");
        ByteArrayOutputStream out = new ByteArrayOutputStream(65 + 9 + filterHashes.size() * 32);
        out.write(filterType);
        out.writeBytes(stopHash.bytes());
        out.writeBytes(previousHeader.bytes());
        out.writeBytes(CompactSize.encode(filterHashes.size()));
        for (Hash256 hash : filterHashes) out.writeBytes(Objects.requireNonNull(hash, "filter hash").bytes());
        return out.toByteArray();
    }

    public static byte[] encodeCfCheckpt(int filterType, Hash256 stopHash, List<Hash256> headers) {
        Objects.requireNonNull(stopHash, "stopHash");
        Objects.requireNonNull(headers, "headers");
        ByteArrayOutputStream out = new ByteArrayOutputStream(33 + 9 + headers.size() * 32);
        out.write(filterType);
        out.writeBytes(stopHash.bytes());
        out.writeBytes(CompactSize.encode(headers.size()));
        for (Hash256 header : headers) out.writeBytes(Objects.requireNonNull(header, "filter header").bytes());
        return out.toByteArray();
    }

    public static List<Hash256> decodeHashVector(byte[] payload, int offset, int maxCount) {
        CompactSize.Decoded decoded = CompactSize.decode(payload, offset);
        if (decoded.value() > maxCount) throw new IllegalArgumentException("BIP157 hash vector exceeds limit");
        int cursor = Math.addExact(offset, decoded.bytesRead());
        int count = Math.toIntExact(decoded.value());
        if (payload.length - cursor != Math.multiplyExact(count, Hash256.LENGTH)) throw new IllegalArgumentException("Invalid BIP157 hash vector length");
        List<Hash256> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++, cursor += Hash256.LENGTH) result.add(new Hash256(slice(payload, cursor, Hash256.LENGTH)));
        return List.copyOf(result);
    }

    private static void writeUInt32LE(ByteArrayOutputStream out, long value) {
        for (int i = 0; i < 4; i++) out.write((int) (value >>> (8 * i)) & 0xff);
    }

    private static long readUInt32LE(byte[] value, int offset) {
        long result = 0;
        for (int i = 0; i < 4; i++) result |= (long) (value[offset + i] & 0xff) << (8 * i);
        return result;
    }

    private static byte[] slice(byte[] value, int offset, int length) {
        if (offset < 0 || length < 0 || offset + length > value.length) throw new IllegalArgumentException("Truncated BIP157 payload");
        byte[] result = new byte[length];
        System.arraycopy(value, offset, result, 0, length);
        return result;
    }
}
