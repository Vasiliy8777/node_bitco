package ru.bitcoin.node.p2p.address;

import java.io.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Versioned, atomic disk persistence for AddrMan state.
 */
public final class PeerAddressManagerStore {
    private static final int MAGIC = 0x50414452; // PADR
    private static final int VERSION = 1;
    private final Path file;
    private final long networkMagic;

    public PeerAddressManagerStore(Path dataDirectory, long networkMagic) {
        Objects.requireNonNull(dataDirectory, "dataDirectory");
        this.file = dataDirectory.resolve("peers.dat");
        this.networkMagic = networkMagic;
    }

    public Optional<PeerAddressManager> load() throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid peers.dat magic");
            if (in.readInt() != VERSION) throw new IOException("Unsupported peers.dat version");
            if (in.readLong() != networkMagic)
                throw new IOException("peers.dat belongs to a different Bitcoin network");
            byte[] secret = in.readNBytes(32);
            if (secret.length != 32) throw new EOFException("Truncated AddrMan secret");
            int count = bounded(in.readInt(), 0, 100_000, "entry count");
            List<PeerAddressManager.EntrySnapshot> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) entries.add(readEntry(in));
            int bucketCount = bounded(in.readInt(), 0, (PeerAddressManager.NEW_BUCKET_COUNT + PeerAddressManager.TRIED_BUCKET_COUNT) * PeerAddressManager.BUCKET_SIZE, "bucket count");
            List<PeerAddressManager.BucketSnapshot> buckets = new ArrayList<>(bucketCount);
            for (int i = 0; i < bucketCount; i++)
                buckets.add(new PeerAddressManager.BucketSnapshot(in.readBoolean(), in.readInt(), in.readInt(), readAddress(in)));
            if (in.read() != -1) throw new IOException("Trailing data in peers.dat");
            return Optional.of(PeerAddressManager.restore(new PeerAddressManager.Snapshot(secret, entries, buckets)));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid peers.dat contents", e);
        }
    }

    public void save(PeerAddressManager manager) throws IOException {
        Objects.requireNonNull(manager, "manager");
        PeerAddressManager.Snapshot snapshot = manager.snapshot();
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(networkMagic);
            out.write(snapshot.secretKey());
            out.writeInt(snapshot.entries().size());
            for (var entry : snapshot.entries()) writeEntry(out, entry);
            out.writeInt(snapshot.buckets().size());
            for (var bucket : snapshot.buckets()) {
                out.writeBoolean(bucket.tried());
                out.writeInt(bucket.bucket());
                out.writeInt(bucket.slot());
                writeAddress(out, bucket.address());
            }
        }
        try {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void writeEntry(DataOutputStream out, PeerAddressManager.EntrySnapshot e) throws IOException {
        writeAddress(out, e.address());
        writeSource(out, e.source());
        writeInstant(out, e.firstSeen());
        writeInstant(out, e.lastSeen());
        writeNullableInstant(out, e.lastAttempt());
        writeNullableInstant(out, e.lastSuccess());
        out.writeInt(e.attempts());
        out.writeByte(e.state().ordinal());
        out.writeInt(e.newBucketReferences());
    }

    private static PeerAddressManager.EntrySnapshot readEntry(DataInputStream in) throws IOException {
        PeerAddress a = readAddress(in);
        PeerAddressSource source = readSource(in);
        Instant first = readInstant(in), seen = readInstant(in);
        Instant attempt = readNullableInstant(in), success = readNullableInstant(in);
        int attempts = bounded(in.readInt(), 0, Integer.MAX_VALUE, "attempts");
        int state = in.readUnsignedByte();
        if (state >= AddrManState.values().length) throw new IOException("Invalid AddrMan state");
        int refs = bounded(in.readInt(), 0, PeerAddressManager.MAX_NEW_REFERENCES, "NEW references");
        return new PeerAddressManager.EntrySnapshot(a, source, first, seen, attempt, success, attempts, AddrManState.values()[state], refs);
    }

    private static void writeAddress(DataOutputStream out, PeerAddress a) throws IOException {
        out.writeByte(a.network().ordinal());
        byte[] raw = a.rawAddress();
        out.writeByte(raw.length);
        out.write(raw);
        out.writeInt(a.port());
        out.writeLong(a.services());
    }

    private static PeerAddress readAddress(DataInputStream in) throws IOException {
        PeerAddressNetwork n = network(in.readUnsignedByte());
        int len = in.readUnsignedByte();
        byte[] raw = in.readNBytes(len);
        if (raw.length != len) throw new EOFException();
        return new PeerAddress(n, raw, in.readInt(), in.readLong());
    }

    private static void writeSource(DataOutputStream out, PeerAddressSource s) throws IOException {
        out.writeByte(s.network().ordinal());
        byte[] raw = s.rawAddress();
        out.writeByte(raw.length);
        out.write(raw);
    }

    private static PeerAddressSource readSource(DataInputStream in) throws IOException {
        PeerAddressNetwork n = network(in.readUnsignedByte());
        int len = in.readUnsignedByte();
        byte[] raw = in.readNBytes(len);
        if (raw.length != len) throw new EOFException();
        return PeerAddressSource.fromRaw(n, raw);
    }

    private static PeerAddressNetwork network(int ordinal) throws IOException {
        if (ordinal < 0 || ordinal >= PeerAddressNetwork.values().length)
            throw new IOException("Invalid address network");
        return PeerAddressNetwork.values()[ordinal];
    }

    private static void writeInstant(DataOutputStream out, Instant i) throws IOException {
        out.writeLong(i.getEpochSecond());
        out.writeInt(i.getNano());
    }

    private static Instant readInstant(DataInputStream in) throws IOException {
        try {
            return Instant.ofEpochSecond(in.readLong(), in.readInt());
        } catch (RuntimeException e) {
            throw new IOException("Invalid timestamp", e);
        }
    }

    private static void writeNullableInstant(DataOutputStream out, Instant i) throws IOException {
        out.writeBoolean(i != null);
        if (i != null) writeInstant(out, i);
    }

    private static Instant readNullableInstant(DataInputStream in) throws IOException {
        return in.readBoolean() ? readInstant(in) : null;
    }

    private static int bounded(int value, int min, int max, String name) throws IOException {
        if (value < min || value > max) throw new IOException("Invalid " + name + ": " + value);
        return value;
    }
}
