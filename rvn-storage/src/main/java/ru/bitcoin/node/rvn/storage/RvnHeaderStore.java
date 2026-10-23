package ru.bitcoin.node.rvn.storage;

import org.rocksdb.*;
import ru.bitcoin.node.rvn.consensus.*;
import ru.bitcoin.node.rvn.protocol.*;
import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Independent mainnet RVN header DB. No Bitcoin storage classes or column families are used.
 * Local DB metadata is trusted like other local node databases; importing an untrusted DB
 * requires full revalidation. P2P never supplies persisted IDs, heights or chainwork.
 */
public final class RvnHeaderStore implements AutoCloseable, RvnHeaderLookup {
    static { RocksDB.loadLibrary(); }
    private static final byte[] SCHEMA = "rvn.headers.schema".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BEST = "rvn.headers.best".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] COUNT = "rvn.headers.count".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] BODY_CURSOR = "rvn.blocks.downloadCursor".getBytes(StandardCharsets.US_ASCII);
    private final RocksDB db;
    private final Options options;
    private final WriteOptions durable;
    private final BigInteger powLimit;
    private final RvnHash256 genesis;
    private final Map<RvnHash256, RvnStoredHeader> cache = new LinkedHashMap<>(256, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<RvnHash256, RvnStoredHeader> entry) {
            return size() > 8192;
        }
    };
    private RvnStoredHeader best;
    private long count;
    private boolean closed;
    private boolean failed;

    /** Callers must independently check the fixed genesis header before constructing this store. */
    public RvnHeaderStore(Path directory, RvnHash256 genesisId, byte[] genesisRaw, BigInteger powLimit)
            throws IOException {
        this.genesis = Objects.requireNonNull(genesisId);
        this.powLimit = Objects.requireNonNull(powLimit);
        byte[] identity = ("RVN-MAINNET-HEADERS-1\n" + genesisId + "\n").getBytes(StandardCharsets.US_ASCII);
        guardDirectory(directory.toAbsolutePath().normalize(), identity);
        durable = new WriteOptions().setSync(true);
        Options openedOptions = new Options().setCreateIfMissing(true).setParanoidChecks(true)
                .setWriteBufferSize(32L * 1024 * 1024).setMaxWriteBufferNumber(2);
        RocksDB openedDb = null;
        try {
            openedDb = RocksDB.open(openedOptions, directory.toAbsolutePath().toString());
            db = openedDb; options = openedOptions;
            byte[] schema = db.get(SCHEMA);
            if (schema == null) {
                try (var iterator = db.newIterator()) {
                    iterator.seekToFirst();
                    if (iterator.isValid()) throw new IOException("Existing DB has no RVN schema; refusing to modify it");
                    iterator.status();
                }
                best = new RvnStoredHeader(genesisId, 0, RvnBlockProof.fromBits(
                        RvnBlockHeader.parse(genesisRaw, RvnActivation.MAINNET_KAWPOW_TIME).compactBits(), powLimit),
                        genesisId, genesisRaw);
                try (var batch = new WriteBatch()) {
                    batch.put(SCHEMA, identity); batch.put(key(genesisId), encode(best));
                    batch.put(BEST, genesisId.wire()); batch.put(COUNT, longBytes(1));
                    db.write(durable, batch);
                }
                count = 1;
            } else {
                if (!Arrays.equals(schema, identity)) throw new IOException("RVN DB network/schema mismatch");
                byte[] bestId = db.get(BEST), countBytes = db.get(COUNT);
                if (bestId == null || bestId.length != 32 || countBytes == null || countBytes.length != 8)
                    throw new IOException("Missing RVN tip/count metadata");
                count = ByteBuffer.wrap(countBytes).getLong();
                best = require(new RvnHash256(bestId));
                var storedGenesis = require(genesisId);
                if (count < 1 || storedGenesis.height() != 0 || !Arrays.equals(storedGenesis.raw(), genesisRaw))
                    throw new IOException("RVN genesis/count mismatch");
                validateTip(best);
            }
        } catch (Exception e) {
            if (openedDb != null) openedDb.close();
            openedOptions.close(); durable.close();
            if (e instanceof IOException io) throw io;
            throw new IOException("Cannot open RVN header DB: " + directory, e);
        }
    }

    private static void guardDirectory(Path path, byte[] identity) throws IOException {
        Files.createDirectories(path);
        Path marker = path.resolve("RVN_HEADERS");
        if (!Files.exists(marker)) {
            try (var contents = Files.list(path)) {
                if (contents.findAny().isPresent())
                    throw new IOException("Nonempty directory is not an RVN header database: " + path);
            }
            try { Files.write(marker, identity, StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException race) { /* another opener must have written the same identity */ }
        }
        if (!Arrays.equals(Files.readAllBytes(marker), identity))
            throw new IOException("RVN directory identity mismatch: " + path);
        if (!Files.exists(path.resolve("CURRENT"))) {
            try (var contents = Files.list(path)) {
                if (contents.anyMatch(entry -> !entry.getFileName().toString().equals("RVN_HEADERS")))
                    throw new IOException("RVN database files exist but CURRENT is missing; refusing to reset the chain");
            }
        }
    }

    @FunctionalInterface
    public interface Validator {
        /** Must check PoW, expected difficulty, timestamps and header context; returns locally computed ID. */
        RvnHash256 verify(byte[] raw, RvnHeaderLookup branch) throws IOException;
    }

    /** Entire batch and best-header pointer commit together. Validation failure leaves disk and memory unchanged. */
    public synchronized int accept(List<byte[]> headers, Validator validator) throws IOException {
        ensureOpen(); Objects.requireNonNull(validator);
        if (headers == null || headers.size() > 2000) throw new IllegalArgumentException("Header batch limit");
        Map<RvnHash256, RvnStoredHeader> staged = new LinkedHashMap<>();
        RvnHeaderLookup view = id -> staged.containsKey(id) ? staged.get(id) : find(id);
        RvnStoredHeader nextBest = best;
        RvnHash256 previous = null;
        for (byte[] supplied : headers) {
            byte[] raw = supplied.clone();
            var parsed = RvnBlockHeader.parse(raw, RvnActivation.MAINNET_KAWPOW_TIME);
            var parentId = new RvnHash256(parsed.previousHashWire());
            if (previous != null && !parentId.equals(previous)) throw new SecurityException("Non-contiguous RVN batch");
            var parent = view.require(parentId);
            long height = Math.addExact(parent.height(), 1);
            if (parsed.isKawpow() && parsed.declaredHeight() != height)
                throw new SecurityException("RVN declared height mismatch");
            RvnHash256 id = Objects.requireNonNull(validator.verify(raw.clone(), view));
            if (id.isZero() || id.equals(genesis) || id.equals(parentId)) throw new SecurityException("Invalid RVN header ID");
            previous = id;
            var existing = view.find(id);
            if (existing != null) {
                if (!Arrays.equals(existing.raw(), raw)) throw new SecurityException("Conflicting RVN header bytes");
                continue;
            }
            BigInteger work = RvnBlockProof.fromBits(parsed.compactBits(), powLimit);
            var skip = view.ancestor(parent, RvnHeaderLookup.skipHeight(height));
            var entry = new RvnStoredHeader(id, height, parent.chainWork().add(work), skip.id(), raw);
            staged.put(id, entry);
            if (entry.chainWork().compareTo(nextBest.chainWork()) > 0) nextBest = entry;
        }
        if (staged.isEmpty()) return 0;
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("RVN batch interrupted before commit");
        long nextCount = Math.addExact(count, staged.size());
        try (var batch = new WriteBatch()) {
            for (var header : staged.values()) batch.put(key(header.id()), encode(header));
            batch.put(BEST, nextBest.id().wire()); batch.put(COUNT, longBytes(nextCount));
            db.write(durable, batch);
        } catch (RocksDBException e) {
            failed = true;
            throw new IOException("RVN header batch commit failed; store must be reopened", e);
        }
        cache.putAll(staged); best = nextBest; count = nextCount;
        return staged.size();
    }

    @Override public synchronized RvnStoredHeader find(RvnHash256 id) throws IOException {
        ensureOpen(); Objects.requireNonNull(id);
        var cached = cache.get(id);
        if (cached != null) return cached;
        try {
            byte[] bytes = db.get(key(id));
            if (bytes == null) return null;
            var entry = decode(id, bytes); cache.put(id, entry); return entry;
        } catch (RocksDBException | IllegalArgumentException | IOException e) {
            failed = true;
            throw new IOException("Invalid RVN header record: " + id, e);
        }
    }

    public synchronized RvnStoredHeader best() { ensureOpen(); return best; }
    public synchronized long count() { ensureOpen(); return count; }

    /** Downloaded bodies are candidates, never a validated block/UTXO tip. */
    public synchronized RvnStoredHeader downloadedBodyTip() throws IOException {
        ensureOpen();
        try {
            byte[] bytes = db.get(BODY_CURSOR);
            if (bytes == null) return require(genesis);
            if (bytes.length != 32) { failed = true; throw new IOException("Invalid RVN body cursor"); }
            var tip = find(new RvnHash256(bytes));
            if (tip == null || (tip.height() > 0 && !hasBlockBody(tip.id()))) {
                failed = true; throw new IOException("Missing RVN downloaded body/header at cursor");
            }
            return tip;
        } catch (RocksDBException e) { failed = true; throw new IOException("RVN body cursor read failed", e); }
    }

    public synchronized boolean hasBlockBody(RvnHash256 id) throws IOException {
        ensureOpen();
        try {
            byte[] digest = db.get(bodyKey(3, id));
            if (digest != null && digest.length != 32) { failed = true; throw new IOException("Invalid RVN body metadata"); }
            return digest != null;
        }
        catch (RocksDBException e) { failed = true; throw new IOException("RVN body metadata read failed", e); }
    }

    /** Rewinds the download cursor on a header reorg; cached off-branch bodies remain available. */
    public synchronized RvnStoredHeader nextMissingBlockBody() throws IOException {
        ensureOpen();
        var previous = downloadedBodyTip();
        var cursor = commonAncestor(previous, best);
        RvnStoredHeader missing = null;
        while (cursor.height() < best.height()) {
            var next = ancestor(best, cursor.height() + 1);
            if (!hasBlockBody(next.id())) { missing = next; break; }
            cursor = next;
        }
        if (!cursor.id().equals(previous.id())) {
            try { db.put(durable, BODY_CURSOR, cursor.id().wire()); }
            catch (RocksDBException e) { failed = true; throw new IOException("RVN body cursor update failed", e); }
        }
        return missing;
    }

    @FunctionalInterface public interface BodyChecks {
        void verify(byte[] payload, RvnStoredHeader header) throws IOException;
    }

    /** Body and contiguous download cursor commit together after commitment/shape checks. */
    public synchronized boolean saveDownloadedBody(RvnHash256 id, byte[] supplied, BodyChecks checks) throws IOException {
        ensureOpen(); Objects.requireNonNull(checks);
        if (supplied == null || supplied.length > 8_000_000) throw new IOException("RVN block body size");
        byte[] payload = supplied.clone();
        var header = require(id); byte[] rawHeader = header.raw();
        if (payload.length <= rawHeader.length || !Arrays.equals(rawHeader, Arrays.copyOf(payload, rawHeader.length)))
            throw new SecurityException("RVN block body/header mismatch");
        checks.verify(payload.clone(), header);
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("RVN body commit interrupted");
        byte[] hash = RvnWire.doubleSha(payload);
        try {
            byte[] previous = db.get(bodyKey(3, id));
            if (previous != null) {
                if (previous.length != 32) { failed = true; throw new IOException("Invalid RVN body metadata"); }
                if (!Arrays.equals(previous, hash)) throw new SecurityException("Conflicting RVN block body");
                return false;
            }
            var cursor = downloadedBodyTip();
            boolean advance = header.parentId().equals(cursor.id())
                    && header.height() <= best.height() && ancestor(best, header.height()).id().equals(id);
            try (var batch = new WriteBatch()) {
                batch.put(bodyKey(2, id), payload); batch.put(bodyKey(3, id), hash);
                if (advance) batch.put(BODY_CURSOR, id.wire());
                db.write(durable, batch);
            }
            return true;
        } catch (RocksDBException e) { failed = true; throw new IOException("RVN block body commit failed", e); }
    }

    public synchronized byte[] blockBody(RvnHash256 id) throws IOException {
        ensureOpen();
        try {
            byte[] payload = db.get(bodyKey(2, id)), digest = db.get(bodyKey(3, id));
            if (payload == null && digest == null) return null;
            if (payload == null || digest == null || !Arrays.equals(RvnWire.doubleSha(payload), digest)) {
                failed = true;
                throw new IOException("RVN stored block body integrity failure");
            }
            return payload;
        } catch (RocksDBException e) { failed = true; throw new IOException("RVN block body read failed", e); }
    }

    private static byte[] bodyKey(int prefix, RvnHash256 id) { byte[] key = key(id); key[0] = (byte) prefix; return key; }

    public synchronized List<byte[]> locators() throws IOException {
        return locators(best.id());
    }

    public synchronized List<byte[]> locators(RvnHash256 tip) throws IOException {
        ensureOpen();
        List<byte[]> result = new ArrayList<>();
        var cursor = require(tip); long step = 1;
        while (result.size() < 100) {
            result.add(cursor.id().wire());
            if (cursor.height() == 0) break;
            if (result.size() > 10) step = Math.min(step * 2, Long.MAX_VALUE / 2);
            cursor = ancestor(cursor, Math.max(0, cursor.height() - step));
        }
        if (!Arrays.equals(result.getLast(), genesis.wire())) result.add(genesis.wire());
        return List.copyOf(result);
    }

    private void validateTip(RvnStoredHeader tip) throws IOException {
        if (tip.height() == 0) {
            if (!tip.id().equals(genesis)) throw new IOException("Invalid RVN genesis tip");
            return;
        }
        var parent = require(tip.parentId());
        if (parent.height() + 1 != tip.height() || !parent.chainWork().add(
                RvnBlockProof.fromBits(tip.bits(), powLimit)).equals(tip.chainWork()))
            throw new IOException("Inconsistent RVN tip metadata");
        if (!ancestor(tip, 0).id().equals(genesis)) throw new IOException("RVN tip has a different genesis");
    }

    private static byte[] key(RvnHash256 id) {
        byte[] key = new byte[33]; key[0] = 1; System.arraycopy(id.wire(), 0, key, 1, 32); return key;
    }
    private static byte[] longBytes(long value) { return ByteBuffer.allocate(8).putLong(value).array(); }
    private static byte[] encode(RvnStoredHeader header) {
        byte[] work = header.chainWork().toByteArray(), fixed = new byte[32];
        int size = Math.min(32, work.length);
        System.arraycopy(work, work.length - size, fixed, 32 - size, size);
        byte[] raw = header.raw();
        return ByteBuffer.allocate(8 + 32 + 32 + raw.length).putLong(header.height())
                .put(fixed).put(header.skipId().wire()).put(raw).array();
    }
    private static RvnStoredHeader decode(RvnHash256 id, byte[] bytes) throws IOException {
        if (bytes.length != 152 && bytes.length != 192) throw new IOException("RVN header record length");
        var buffer = ByteBuffer.wrap(bytes); long height = buffer.getLong();
        byte[] work = new byte[32], skip = new byte[32], raw = new byte[bytes.length - 72];
        buffer.get(work).get(skip).get(raw);
        return new RvnStoredHeader(id, height, new BigInteger(1, work), new RvnHash256(skip), raw);
    }
    private void ensureOpen() {
        if (closed) throw new IllegalStateException("RVN header DB is closed");
        if (failed) throw new IllegalStateException("RVN header DB failed; close and reopen before use");
    }
    @Override public synchronized void close() {
        if (!closed) { closed = true; cache.clear(); db.close(); durable.close(); options.close(); }
    }
}
