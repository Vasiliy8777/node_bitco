package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Bitcoin Core v2 dumptxoutset-compatible streaming snapshot writer.
 */
public final class UtxoSnapshotWriter {
    public static final byte[] MAGIC = {'u', 't', 'x', 'o', (byte) 0xff};
    public static final int VERSION = 2;
    private static final byte DEFAULT_UTXO_PREFIX = 0x03;

    private UtxoSnapshotWriter() {
    }

    public record Result(long coinsWritten, Hash256 baseHash, long baseHeight, Path path) {
    }

    public static Result write(RocksDbDatabase database, long networkMagic, Hash256 baseHash,
                               long baseHeight, Path target) throws IOException {
        return write(database, DEFAULT_UTXO_PREFIX, networkMagic, baseHash, baseHeight, target);
    }

    public static Result write(RocksDbDatabase database, byte utxoPrefix, long networkMagic, Hash256 baseHash,
                               long baseHeight, Path target) throws IOException {
        Objects.requireNonNull(database);
        Objects.requireNonNull(baseHash);
        Objects.requireNonNull(target);
        Path absolute = target.toAbsolutePath().normalize();
        if (Files.exists(absolute)) throw new FileAlreadyExistsException(absolute.toString());
        Path parent = absolute.getParent();
        if (parent != null) Files.createDirectories(parent);
        Path temp = absolute.resolveSibling(absolute.getFileName() + ".incomplete");
        Files.deleteIfExists(temp);
        long count = database.countPrefix(utxoPrefix);
        try (OutputStream raw = new BufferedOutputStream(Files.newOutputStream(temp, StandardOpenOption.CREATE_NEW))) {
            raw.write(MAGIC);
            writeLE(raw, VERSION, 2);
            writeLE(raw, networkMagic, 4);
            raw.write(baseHash.bytes());
            writeLE(raw, count, 8);
            writeBody(database, utxoPrefix, raw);
        } catch (UncheckedIOException e) {
            Files.deleteIfExists(temp);
            throw e.getCause();
        } catch (RuntimeException | IOException e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        try {
            Files.move(temp, absolute, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, absolute);
        }
        return new Result(count, baseHash, baseHeight, absolute);
    }

    private static void writeBody(RocksDbDatabase db, byte utxoPrefix, OutputStream out) {
        final byte[][] current = {null};
        final List<Coin> group = new ArrayList<>();
        Runnable flush = () -> {
            if (current[0] == null) return;
            try {
                out.write(current[0]);
                out.write(CompactSize.encode(group.size()));
                for (Coin c : group) {
                    out.write(CompactSize.encode(c.vout));
                    writeCoin(out, c.coin);
                }
                group.clear();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        };
        db.forEachEntryByPrefix(utxoPrefix, (key, value) -> {
            if (key.length != 37) throw new IllegalStateException("Invalid UTXO key length: " + key.length);
            byte[] txid = Arrays.copyOfRange(key, 1, 33);
            if (current[0] != null && !Arrays.equals(current[0], txid)) flush.run();
            if (current[0] == null || !Arrays.equals(current[0], txid)) current[0] = txid;
            long vout = (key[33] & 255L) | ((key[34] & 255L) << 8) | ((key[35] & 255L) << 16) | ((key[36] & 255L) << 24);
            group.add(new Coin(vout, StoredUtxoSerializer.deserialize(value)));
        });
        flush.run();
    }

    private record Coin(long vout, StoredUtxo coin) {
    }

    private static void writeCoin(OutputStream out, StoredUtxo coin) throws IOException {
        UtxoSnapshotCoinCodec.write(out, coin);
    }

    static long compressAmount(long n) {
        if (n < 0) throw new IllegalArgumentException("negative amount");
        if (n == 0) return 0;
        int e = 0;
        while (n % 10 == 0 && e < 9) {
            n /= 10;
            e++;
        }
        if (e < 9) {
            long d = n % 10;
            n /= 10;
            return 1 + (n * 9 + d - 1) * 10 + e;
        }
        return 1 + (n - 1) * 10 + 9;
    }

    static void writeVarInt(OutputStream out, long n) throws IOException {
        if (n < 0) throw new IllegalArgumentException("negative VARINT");
        byte[] tmp = new byte[10];
        int len = 0;
        while (true) {
            tmp[len] = (byte) ((n & 0x7f) | (len > 0 ? 0x80 : 0));
            if (n <= 0x7f) break;
            n = (n >>> 7) - 1;
            len++;
        }
        do out.write(tmp[len]); while (len-- != 0);
    }

    private static void writeLE(OutputStream out, long v, int bytes) throws IOException {
        for (int i = 0; i < bytes; i++) out.write((int) (v >>> (8 * i)) & 255);
    }
}
