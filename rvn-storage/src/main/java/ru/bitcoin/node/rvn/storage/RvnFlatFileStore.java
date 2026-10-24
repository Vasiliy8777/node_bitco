package ru.bitcoin.node.rvn.storage;

import ru.bitcoin.node.rvn.protocol.RvnWire;
import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Append-only cold storage. force(true) completes before an index may publish
 * a reference. A crash before index commit leaves unreachable bytes, never a
 * committed index pointing at an intentionally unflushed record. No automatic
 * truncation/GC: live references may still belong to an old branch or undo. */
public final class RvnFlatFileStore implements AutoCloseable {
    private static final int MAGIC = 0x52564e31, HEADER = 40, REFERENCE = 48;
    private static final long SEGMENT_BYTES = 128L * 1024 * 1024;
    private final Path directory;
    private final int maximum;
    private final long segmentBytes;
    private final FileChannel lockChannel;
    private final FileLock lock;
    private FileChannel writer;
    private int segment;
    private boolean closed, failed;

    public RvnFlatFileStore(Path directory, String identity, int maximum) throws IOException {
        this(directory, identity, maximum, SEGMENT_BYTES);
    }

    RvnFlatFileStore(Path directory, String identity, int maximum, long segmentBytes) throws IOException {
        if (maximum < 1 || maximum > 64 * 1024 * 1024) throw new IllegalArgumentException("RVN archive record limit");
        if (segmentBytes < maximum + HEADER) throw new IllegalArgumentException("RVN archive segment limit");
        this.segmentBytes = segmentBytes;
        this.directory = directory.toAbsolutePath().normalize(); this.maximum = maximum;
        Files.createDirectories(this.directory);
        Path marker = this.directory.resolve("RVN_ARCHIVE");
        byte[] expected = ("RVN-FLAT-1\n" + identity + "\n").getBytes(StandardCharsets.US_ASCII);
        if (!Files.exists(marker)) {
            try (var files = Files.list(this.directory)) {
                if (files.findAny().isPresent()) throw new IOException("Refusing foreign RVN archive directory");
            }
            try { Files.write(marker, expected, StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException race) { /* verify identity below */ }
        }
        if (!Arrays.equals(expected, Files.readAllBytes(marker))) throw new IOException("RVN archive identity mismatch");
        FileChannel opened = FileChannel.open(this.directory.resolve("LOCK"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = opened.tryLock();
            if (acquired == null) throw new IOException("RVN archive already locked");
            try (var files = Files.list(this.directory)) {
                for (var path : files.toList()) {
                    String name = path.getFileName().toString();
                    if (name.matches("segment-[0-9]{8}\\.dat")) segment = Math.max(segment, Integer.parseInt(name.substring(8, 16)));
                }
            }
            lockChannel = opened; lock = acquired;
        } catch (Exception e) {
            if (acquired != null) acquired.release(); opened.close();
            if (e instanceof IOException io) throw io;
            throw new IOException("Cannot lock RVN archive", e);
        }
    }

    /** Immutable opaque reference: segment, payload offset, length and checksum. */
    public synchronized byte[] append(byte[] supplied) throws IOException {
        ensureOpen(); Objects.requireNonNull(supplied);
        if (supplied.length < 1 || supplied.length > maximum) throw new IOException("RVN archive payload limit");
        byte[] payload = supplied.clone(), hash = RvnWire.doubleSha(payload);
        try {
            if (writer == null) writer = FileChannel.open(path(segment), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            if (writer.size() + HEADER + payload.length > segmentBytes && writer.size() != 0) {
                writer.close(); writer = null;
                if (++segment > 99_999_999) throw new IOException("RVN archive segment limit");
                writer = FileChannel.open(path(segment), StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            }
            long offset = writer.size(); writer.position(offset);
            writeFully(writer, ByteBuffer.allocate(HEADER).putInt(MAGIC).putInt(payload.length).put(hash).flip());
            writeFully(writer, ByteBuffer.wrap(payload));
            writer.force(true);
            return ByteBuffer.allocate(REFERENCE).putInt(segment).putLong(offset + HEADER).putInt(payload.length).put(hash).array();
        } catch (IOException e) { failed = true; throw e; }
    }

    public synchronized byte[] read(byte[] reference) throws IOException {
        ensureOpen();
        if (reference == null || reference.length != REFERENCE) throw new IOException("RVN archive reference size");
        var in = ByteBuffer.wrap(reference); int file = in.getInt(); long offset = in.getLong(); int size = in.getInt();
        byte[] hash = new byte[32]; in.get(hash);
        if (file < 0 || file > 99_999_999 || offset < HEADER || size < 1 || size > maximum || offset > Long.MAX_VALUE - size)
            throw new IOException("RVN archive reference range");
        try (var channel = FileChannel.open(path(file), StandardOpenOption.READ)) {
            if (offset + size > channel.size()) throw new IOException("Truncated RVN archive record");
            var header = ByteBuffer.allocate(HEADER); readFully(channel, header, offset - HEADER); header.flip();
            int magic = header.getInt(), storedSize = header.getInt(); byte[] storedHash = new byte[32]; header.get(storedHash);
            if (magic != MAGIC || storedSize != size || !Arrays.equals(hash, storedHash)) throw new IOException("RVN archive header mismatch");
            byte[] payload = new byte[size]; readFully(channel, ByteBuffer.wrap(payload), offset);
            if (!Arrays.equals(hash, RvnWire.doubleSha(payload))) throw new IOException("RVN archive checksum mismatch");
            return payload;
        }
    }

    private Path path(int number) { return directory.resolve(String.format(Locale.ROOT, "segment-%08d.dat", number)); }
    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) if (channel.write(bytes) <= 0) throw new IOException("RVN archive write stalled");
    }
    private static void readFully(FileChannel channel, ByteBuffer bytes, long offset) throws IOException {
        while (bytes.hasRemaining()) {
            int count = channel.read(bytes, offset);
            if (count <= 0) throw new EOFException("Truncated RVN archive read");
            offset += count;
        }
    }
    private void ensureOpen() {
        if (closed || failed) throw new IllegalStateException("RVN archive closed/failed; reopen required");
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        try { if (writer != null) writer.close(); }
        finally { try { lock.release(); } finally { lockChannel.close(); } }
    }
}
