package ru.bitcoin.node.rvn.p2p;

import ru.bitcoin.node.rvn.protocol.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.CRC32;

import static java.nio.file.StandardCopyOption.*;

/**
 * Bounded, crash-recoverable archive of unverified wire headers batches.
 * Files are NOT chain state. Never use their digest as a block hash or locator.
 */
public final class RvnUnverifiedHeaderSpool {
    private static final int MAGIC = 0x52565336; // RVS6
    private static final int MAX_PAYLOAD = 250_000;
    private final Path directory;
    private final long activationTime;
    private final int maxFiles;

    public RvnUnverifiedHeaderSpool(Path directory, long activationTime, int maxFiles) throws IOException {
        this.directory = Objects.requireNonNull(directory).toAbsolutePath().normalize();
        if (maxFiles < 1 || maxFiles > 100_000) throw new IllegalArgumentException("maxFiles");
        this.activationTime = activationTime;
        this.maxFiles = maxFiles;
        Files.createDirectories(this.directory);
        if (!Files.isDirectory(this.directory)) throw new IOException("Not a directory");
    }

    public synchronized String store(RvnHeadersBatch batch) throws IOException {
        Objects.requireNonNull(batch);
        byte[] payload = batch.payload();
        if (payload.length > MAX_PAYLOAD) throw new IOException("Oversized headers payload");
        // Recheck under this spool's own activation policy.
        RvnHeadersBatch.fromPayload(payload, activationTime);
        String id = RvnHex.encode(RvnWire.doubleSha(payload));
        Path destination = directory.resolve(id + ".rvnb");
        if (Files.exists(destination)) {
            read(destination); // corrupt existing files must not silently count as valid duplicates
            return id;
        }
        if (list().size() >= maxFiles) throw new IOException("Spool file limit reached");
        Path temp = Files.createTempFile(directory, ".rvn-", ".tmp");
        try {
            CRC32 crc = new CRC32();
            crc.update(payload);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.writeInt(MAGIC);
                out.writeInt(payload.length);
                out.writeInt((int) crc.getValue());
                out.write(payload);
            }
            try {
                Files.move(temp, destination, ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, destination);
            }
            return id;
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public synchronized RvnHeadersBatch load(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid digest ID");
        return read(directory.resolve(id + ".rvnb"));
    }

    public synchronized List<String> list() throws IOException {
        List<String> result = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(directory, "*.rvnb")) {
            for (Path p : files) {
                String name = p.getFileName().toString();
                if (name.matches("[0-9a-f]{64}\\.rvnb")) result.add(name.substring(0, 64));
            }
        }
        Collections.sort(result);
        return List.copyOf(result);
    }

    public synchronized boolean delete(String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid digest ID");
        return Files.deleteIfExists(directory.resolve(id + ".rvnb"));
    }

    private RvnHeadersBatch read(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (in.readInt() != MAGIC) throw new IOException("Invalid spool magic");
            int length = in.readInt();
            if (length < 1 || length > MAX_PAYLOAD) throw new IOException("Invalid spool payload size");
            int crc = in.readInt();
            byte[] payload = new byte[length];
            in.readFully(payload);
            if (in.read() != -1) throw new IOException("Trailing spool bytes");
            CRC32 check = new CRC32();
            check.update(payload);
            if ((int) check.getValue() != crc) throw new IOException("Spool CRC mismatch");
            String digest = RvnHex.encode(RvnWire.doubleSha(payload));
            if (!file.getFileName().toString().equals(digest + ".rvnb"))
                throw new IOException("Spool content digest mismatch");
            return RvnHeadersBatch.fromPayload(payload, activationTime);
        }
    }
}
