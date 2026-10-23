package ru.bitcoin.node.rvn.consensus;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Append-only journal for header CANDIDATES, never a trust cache.
 * Every record must pass the full verifier again when replayed.
 * SHA-256 protects against accidental corruption, not malicious modification.
 */
public final class RvnVerifiedHeaderJournal {
    private static final byte[] MAGIC = {'R', 'V', 'N', 'J', 1};
    private static final int MAX_RECORDS = 1_000_000;
    private static final int RECORD_SIZE = 32 + 1 + 120 + 32;

    private RvnVerifiedHeaderJournal() {
    }

    public record Record(RvnHash256 claimedId, byte[] raw) {
        public Record {
            Objects.requireNonNull(claimedId);
            if (raw == null || (raw.length != 80 && raw.length != 120))
                throw new IllegalArgumentException("header length");
            raw = raw.clone();
        }

        @Override
        public byte[] raw() {
            return raw.clone();
        }
    }

    private static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void writeAtomically(Path path, List<Record> records) throws IOException {
        Objects.requireNonNull(path);
        Objects.requireNonNull(records);
        if (records.size() > MAX_RECORDS) throw new IllegalArgumentException("record count");
        Path absolute = path.toAbsolutePath();
        Path parent = absolute.getParent();
        if (parent == null) throw new IllegalArgumentException("journal path");
        Files.createDirectories(parent);
        Path temp = Files.createTempFile(parent, ".rvn-headers-", ".tmp");
        try {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temp)))) {
                out.write(MAGIC);
                out.writeInt(records.size());
                for (Record record : records) {
                    Objects.requireNonNull(record);
                    byte[] raw = record.raw();
                    ByteArrayOutputStream payload = new ByteArrayOutputStream(153);
                    payload.write(record.claimedId().wire());
                    payload.write(raw.length);
                    payload.write(raw);
                    byte[] data = payload.toByteArray();
                    out.write(data);
                    out.write(digest(data));
                }
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    public static List<Record> read(Path path) throws IOException {
        Objects.requireNonNull(path);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            byte[] magic = in.readNBytes(MAGIC.length);
            if (!Arrays.equals(magic, MAGIC)) throw new IOException("Unsupported RVN journal format");
            int count = in.readInt();
            if (count < 0 || count > MAX_RECORDS) throw new IOException("Invalid journal count");
            List<Record> records = new ArrayList<>(Math.min(count, 4096));
            for (int i = 0; i < count; i++) {
                byte[] id = in.readNBytes(32);
                if (id.length != 32) throw new EOFException("Truncated block ID");
                int length = in.readUnsignedByte();
                if (length != 80 && length != 120) throw new IOException("Invalid header length");
                byte[] raw = in.readNBytes(length);
                byte[] checksum = in.readNBytes(32);
                if (raw.length != length || checksum.length != 32) throw new EOFException("Truncated record");
                ByteArrayOutputStream payload = new ByteArrayOutputStream(153);
                payload.write(id);
                payload.write(length);
                payload.write(raw);
                if (!MessageDigest.isEqual(checksum, digest(payload.toByteArray())))
                    throw new IOException("Journal record checksum mismatch");
                records.add(new Record(new RvnHash256(id), raw));
            }
            if (in.read() != -1) throw new IOException("Trailing journal data");
            return List.copyOf(records);
        }
    }

    /**
     * Replay into an EMPTY, caller-configured index with a genuine trusted anchor.
     * No disk content is trusted; all records are rechecked by the proof gate.
     * Replay may partially admit records if a later record fails verification.
     */
    public static int replay(Path path, RvnValidatedHeaderIndex index,
                             RvnHeaderFormatPolicy policy,
                             RvnHeaderProofGate.Verifier verifier) throws IOException {
        Objects.requireNonNull(index);
        Objects.requireNonNull(policy);
        Objects.requireNonNull(verifier);
        if (index.size() != 0) throw new IllegalArgumentException("Replay requires an empty index");
        List<Record> records = read(path);
        int count = 0;
        for (Record record : records) {
            RvnHeaderCandidate candidate = RvnHeaderCandidateFactory.unverified(
                    record.claimedId(), record.raw(), policy);
            index.admit(candidate, record.raw(), verifier);
            count++;
        }
        return count;
    }
}
