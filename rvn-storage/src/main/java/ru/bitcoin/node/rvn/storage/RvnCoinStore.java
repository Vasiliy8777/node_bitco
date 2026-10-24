package ru.bitcoin.node.rvn.storage;

import org.rocksdb.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetChanges;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.Metadata;
import ru.bitcoin.node.rvn.consensus.RvnHash256;
import ru.bitcoin.node.rvn.protocol.RvnWire;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Independent durable coin and asset-metadata state. This is a storage primitive, not a
 * full-block validator. Caller must validate consensus before connect; a stored
 * tip alone is never evidence of completed scripts/assets/header validation. */
public final class RvnCoinStore implements RvnCoinView, AutoCloseable {
    static { RocksDB.loadLibrary(); }
    private static final byte[] SCHEMA = {0}, TIP = {1};
    private static final byte[] EXTERNAL_UNDO = {4};
    private final RvnFlatFileStore undoFiles;
    private static final int MAX_UNDO_BYTES = 64 * 1024 * 1024;
    private final RocksDB db;
    private final Options options;
    private final Cache blockCache;
    private final WriteOptions durable;
    private final RvnHash256 genesis;
    private final byte[] assetSchemaIdentity;
    private Tip tip;
    private boolean closed, failed;

    public record Tip(RvnHash256 id, long height) {
        public Tip {
            Objects.requireNonNull(id);
            if (height < 0 || height > 0xffff_ffffL) throw new IllegalArgumentException("RVN coin tip height");
        }
    }

    public RvnCoinStore(Path directory, RvnHash256 genesis) throws IOException {
        this(directory, genesis, null);
    }

    public RvnCoinStore(Path directory, RvnHash256 genesis, RvnFlatFileStore undoFiles) throws IOException {
        this.undoFiles = undoFiles;
        this.genesis = Objects.requireNonNull(genesis);
        byte[] identity = ("RVN-NATIVE-COINS-1\n" + genesis + "\n").getBytes(StandardCharsets.US_ASCII);
        assetSchemaIdentity = ("RVN-COINS-ASSETS-2\n" + genesis + "\n").getBytes(StandardCharsets.US_ASCII);
        guard(directory.toAbsolutePath().normalize(), identity);
        Cache openedCache = new LRUCache(256L * 1024 * 1024, -1, true);
        Options openedOptions = new Options().setCreateIfMissing(true).setParanoidChecks(true)
                .setWriteBufferSize(32L * 1024 * 1024).setMaxWriteBufferNumber(2)
                .setTableFormatConfig(new BlockBasedTableConfig().setBlockCache(openedCache).setCacheIndexAndFilterBlocks(true));
        WriteOptions openedWrites = new WriteOptions().setSync(true);
        RocksDB opened = null;
        try {
            opened = RocksDB.open(openedOptions, directory.toAbsolutePath().toString());
            db = opened; options = openedOptions; durable = openedWrites; blockCache = openedCache;
            byte[] schema = db.get(SCHEMA);
            if (schema == null) {
                try (var iterator = db.newIterator()) {
                    iterator.seekToFirst();
                    if (iterator.isValid()) throw new IOException("Nonempty RVN coin DB without schema");
                    iterator.status();
                }
                tip = new Tip(genesis, 0);
                try (var batch = new WriteBatch()) {
                    batch.put(SCHEMA, identity); batch.put(TIP, encodeTip(tip));
                    db.write(durable, batch);
                }
            } else {
                if (!Arrays.equals(identity, schema) && !Arrays.equals(assetSchemaIdentity, schema))
                    throw new IOException("RVN coin DB identity mismatch");
                tip = decodeTip(db.get(TIP));
                if ((tip.height() == 0) != tip.id().equals(genesis)) throw new IOException("Invalid RVN coin tip");
            }
            byte[] layout = db.get(EXTERNAL_UNDO);
            if (layout != null && (undoFiles == null || !Arrays.equals(layout, new byte[]{1})))
                throw new IOException("RVN coin DB requires its external undo archive");
            if (undoFiles != null && layout == null) db.put(durable, EXTERNAL_UNDO, new byte[]{1});
            if (tip.height() > 0) verifyUndoState(readUndo(tip));
        } catch (Exception e) {
            if (opened != null) opened.close();
            openedWrites.close(); openedOptions.close(); openedCache.close();
            if (e instanceof IOException io) throw io;
            throw new IOException("Cannot open RVN coin DB", e);
        }
    }

    public synchronized Tip tip() { ensureOpen(); return tip; }

    public synchronized Optional<Metadata> findAsset(String name) {
        ensureOpen();
        try {
            byte[] value = db.get(assetKey(name));
            if (value == null) return Optional.empty();
            var metadata = RvnAssetStateCodec.decode(value);
            if (!name.equals(metadata.name())) throw new IOException("RVN asset registry key/name mismatch");
            return Optional.of(metadata);
        } catch (RocksDBException | IOException e) {
            throw new UncheckedIOException(new IOException("Cannot read RVN asset registry", e));
        }
    }

    @Override public synchronized Optional<RvnCoin> find(OutPoint point) {
        ensureOpen();
        try {
            byte[] value = db.get(coinKey(point));
            return value == null ? Optional.empty() : Optional.of(decodeCoin(value));
        } catch (RocksDBException | IOException e) { throw new UncheckedIOException(new IOException("Cannot read RVN coin", e)); }
    }

    /** Synchronously commits all net coin changes, undo and tip in one RocksDB
     * batch. Rejects stale parents and mismatched coins before any write. */
    public synchronized Tip connect(Tip expectedParent, RvnHash256 id, RvnCoinAccounting.Changes changes) throws IOException {
        return connect(expectedParent, id, changes, RvnAssetChanges.EMPTY);
    }

    /** Asset before-values, UTXO changes, shared undo and tip commit in the same
     * synchronous batch. Caller supplies fully validated net block changes. */
    public synchronized Tip connect(Tip expectedParent, RvnHash256 id, RvnCoinAccounting.Changes changes,
                                    RvnAssetChanges assets) throws IOException {
        ensureOpen(); Objects.requireNonNull(id); Objects.requireNonNull(changes); Objects.requireNonNull(assets);
        if (!tip.equals(expectedParent)) throw new IllegalStateException("Stale RVN coin parent");
        if (id.equals(genesis) || id.equals(tip.id())) throw new IllegalArgumentException("Invalid RVN coin block ID");
        try {
            if (db.get(undoKey(id)) != null) throw new IllegalArgumentException("RVN coin block already connected");
        } catch (RocksDBException e) { throw new IOException("Cannot check RVN coin block identity", e); }
        Tip next = new Tip(id, Math.addExact(tip.height(), 1));
        requireDisjoint(changes);
        for (var entry : changes.spent().entrySet()) {
            if (!find(entry.getKey()).equals(Optional.of(entry.getValue())))
                throw new SecurityException("RVN spent coin mismatch");
        }
        for (var entry : changes.created().entrySet()) {
            if (find(entry.getKey()).isPresent() || entry.getValue().height() != next.height())
                throw new SecurityException("RVN created coin collision/height mismatch");
            requireSpendable(entry.getKey(), entry.getValue());
        }
        for (var entry : assets.entries().entrySet())
            if (!findAsset(entry.getKey()).equals(entry.getValue().before()))
                throw new SecurityException("RVN asset before-value mismatch");
        byte[] undo = encodeUndo(tip, changes, assets);
        if (undoFiles != null) undo = undoFiles.append(undo);
        try (var batch = new WriteBatch()) {
            for (var point : changes.spent().keySet()) batch.delete(coinKey(point));
            for (var entry : changes.created().entrySet()) batch.put(coinKey(entry.getKey()), encodeCoin(entry.getValue()));
            applyAssets(batch, assets, false);
            // Upgrade atomically on first asset mutation. Older native-only
            // binaries must refuse this DB even after later native-only blocks.
            if (!assets.entries().isEmpty()) batch.put(SCHEMA, assetSchemaIdentity);
            batch.put(undoKey(id), undo); batch.put(TIP, encodeTip(next));
            write(batch);
            tip = next;
            return tip;
        } catch (RocksDBException e) { throw new IOException("Cannot prepare RVN coin commit", e); }
    }

    /** Disconnects only the current tip. Undo is deleted in the same batch so
     * reconnecting another branch cannot reuse stale undo metadata. */
    public synchronized Tip disconnect(Tip expectedTip) throws IOException {
        ensureOpen();
        if (!tip.equals(expectedTip)) throw new IllegalStateException("Stale RVN coin disconnect");
        if (tip.height() == 0) throw new IllegalStateException("Cannot disconnect RVN genesis");
        Undo undo = readUndo(tip);
        verifyUndoState(undo);
        try (var batch = new WriteBatch()) {
            for (var point : undo.changes().created().keySet()) batch.delete(coinKey(point));
            for (var entry : undo.changes().spent().entrySet()) batch.put(coinKey(entry.getKey()), encodeCoin(entry.getValue()));
            applyAssets(batch, undo.assets(), true);
            batch.delete(undoKey(tip.id())); batch.put(TIP, encodeTip(undo.parent()));
            write(batch);
            tip = undo.parent();
            return tip;
        } catch (RocksDBException e) { throw new IOException("Cannot prepare RVN coin undo", e); }
    }

    private void verifyUndoState(Undo undo) throws IOException {
        for (var entry : undo.changes().created().entrySet()) {
            if (entry.getValue().height() != tip.height() || !find(entry.getKey()).equals(Optional.of(entry.getValue())))
                throw new IOException("RVN undo created coin mismatch");
        }
        for (var point : undo.changes().spent().keySet())
            if (find(point).isPresent()) throw new IOException("RVN undo spent coin already exists");
        for (var entry : undo.assets().entries().entrySet())
            if (!findAsset(entry.getKey()).equals(entry.getValue().after()))
                throw new IOException("RVN undo asset state mismatch");
    }

    private record Undo(Tip parent, RvnCoinAccounting.Changes changes, RvnAssetChanges assets) {}

    private Undo readUndo(Tip current) throws IOException {
        try {
            byte[] encoded = db.get(undoKey(current.id()));
            if (encoded != null && encoded.length == 48) {
                if (undoFiles == null) throw new IOException("Missing RVN external undo archive");
                encoded = undoFiles.read(encoded);
            }
            if (encoded == null || encoded.length < 32 || encoded.length > MAX_UNDO_BYTES)
                throw new IOException("Missing/oversized RVN undo");
            byte[] payload = Arrays.copyOf(encoded, encoded.length - 32);
            if (!Arrays.equals(RvnWire.doubleSha(payload), Arrays.copyOfRange(encoded, payload.length, encoded.length)))
                throw new IOException("Corrupt RVN undo checksum");
            var in = new DataInputStream(new ByteArrayInputStream(payload));
            Tip parent = decodeTip(in.readNBytes(40));
            long fees = in.readLong();
            var changes = new RvnCoinAccounting.Changes(fees, readCoins(in), readCoins(in));
            var assets = RvnAssetStateCodec.readChanges(in);
            requireDisjoint(changes);
            if (in.available() != 0 || parent.height() != current.height() - 1
                    || (parent.height() == 0) != parent.id().equals(genesis)
                    || fees < 0 || fees > RvnBlockBodyChecks.MAX_MONEY)
                throw new IOException("Malformed RVN undo metadata");
            return new Undo(parent, changes, assets);
        } catch (RocksDBException | IllegalArgumentException e) { throw new IOException("Cannot read RVN undo", e); }
    }

    private static byte[] encodeUndo(Tip parent, RvnCoinAccounting.Changes changes, RvnAssetChanges assets) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.write(encodeTip(parent)); out.writeLong(changes.fees());
        writeCoins(out, changes.spent()); writeCoins(out, changes.created());
        RvnAssetStateCodec.writeChanges(out, assets);
        byte[] payload = bytes.toByteArray();
        if (payload.length > MAX_UNDO_BYTES - 32) throw new IOException("RVN undo size limit");
        out.write(RvnWire.doubleSha(payload)); return bytes.toByteArray();
    }

    private static void writeCoins(DataOutputStream out, Map<OutPoint, RvnCoin> coins) throws IOException {
        out.writeInt(coins.size());
        for (var entry : coins.entrySet()) {
            out.write(entry.getKey().transactionId().bytes()); out.writeInt((int) entry.getKey().outputIndex().value());
            byte[] coin = encodeCoin(entry.getValue()); out.writeInt(coin.length); out.write(coin);
        }
    }

    private static Map<OutPoint, RvnCoin> readCoins(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > in.available() / 61) throw new IOException("Invalid RVN undo coin count");
        Map<OutPoint, RvnCoin> coins = new HashMap<>();
        for (int i = 0; i < count; i++) {
            var point = new OutPoint(new Hash256(in.readNBytes(32)), new UInt32(Integer.toUnsignedLong(in.readInt())));
            int length = in.readInt();
            if (length < 21 || length > 10_021 || length > in.available()) throw new IOException("Invalid RVN undo coin length");
            var coin = decodeCoin(in.readNBytes(length));
            requireSpendable(point, coin);
            if (coins.put(point, coin) != null) throw new IOException("Duplicate RVN undo coin");
        }
        return coins;
    }

    private static byte[] encodeCoin(RvnCoin coin) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeLong(coin.output().value()); out.writeLong(coin.height()); out.writeBoolean(coin.coinbase());
        byte[] script = coin.output().scriptPubKey(); out.writeInt(script.length); out.write(script);
        return bytes.toByteArray();
    }

    private static RvnCoin decodeCoin(byte[] value) throws IOException {
        try {
            var in = new DataInputStream(new ByteArrayInputStream(value));
            long amount = in.readLong(), height = in.readLong(); int flag = in.readUnsignedByte(), size = in.readInt();
            if (flag > 1 || size < 0 || size > 10_000 || size != in.available()) throw new IOException("Malformed RVN coin");
            return new RvnCoin(new TxOut(amount, in.readNBytes(size)), height, flag == 1);
        } catch (IllegalArgumentException e) { throw new IOException("Invalid RVN coin", e); }
    }

    private static byte[] encodeTip(Tip tip) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.write(tip.id().wire()); out.writeLong(tip.height()); return bytes.toByteArray();
    }

    private static Tip decodeTip(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length != 40) throw new IOException("Invalid RVN coin tip encoding");
        try {
            var in = new DataInputStream(new ByteArrayInputStream(bytes));
            return new Tip(new RvnHash256(in.readNBytes(32)), in.readLong());
        } catch (IllegalArgumentException e) { throw new IOException("Invalid RVN coin tip", e); }
    }

    private static byte[] coinKey(OutPoint point) {
        byte[] key = new byte[37]; key[0] = 2;
        System.arraycopy(point.transactionId().bytes(), 0, key, 1, 32);
        long index = point.outputIndex().value();
        for (int i = 0; i < 4; i++) key[33 + i] = (byte) (index >>> (8 * i));
        return key;
    }
    private static byte[] undoKey(RvnHash256 id) {
        byte[] key = new byte[33]; key[0] = 3; System.arraycopy(id.wire(), 0, key, 1, 32); return key;
    }
    private static byte[] assetKey(String name) {
        ru.bitcoin.node.rvn.chain.assets.RvnAssetNames.requireValid(name);
        byte[] bytes = name.getBytes(StandardCharsets.US_ASCII);
        byte[] key = new byte[bytes.length + 1]; key[0] = 5;
        System.arraycopy(bytes, 0, key, 1, bytes.length); return key;
    }
    private static void applyAssets(WriteBatch batch, RvnAssetChanges changes, boolean undo) throws IOException, RocksDBException {
        for (var entry : changes.entries().entrySet()) {
            var value = undo ? entry.getValue().before() : entry.getValue().after();
            byte[] key = assetKey(entry.getKey());
            if (value.isPresent()) batch.put(key, RvnAssetStateCodec.encode(value.get()));
            else batch.delete(key);
        }
    }
    private static void requireDisjoint(RvnCoinAccounting.Changes changes) {
        if (changes.fees() < 0 || changes.fees() > RvnBlockBodyChecks.MAX_MONEY
                || !Collections.disjoint(changes.spent().keySet(), changes.created().keySet()))
            throw new IllegalArgumentException("Invalid RVN coin change plan");
    }
    private static void requireSpendable(OutPoint point, RvnCoin coin) throws IOException {
        byte[] script = coin.output().scriptPubKey();
        if (point.isCoinbase() || script.length > 10_000 || (script.length > 0 && script[0] == 0x6a))
            throw new IOException("Unspendable RVN coin in change plan");
    }
    private void write(WriteBatch batch) throws IOException {
        try { db.write(durable, batch); }
        catch (RocksDBException e) { failed = true; throw new IOException("RVN coin write failed; reopen required", e); }
    }
    private void ensureOpen() {
        if (closed || failed) throw new IllegalStateException("RVN coin DB closed or failed");
    }
    @Override public synchronized void close() {
        if (!closed) { closed = true; db.close(); durable.close(); options.close(); blockCache.close(); }
    }

    private static void guard(Path directory, byte[] identity) throws IOException {
        Files.createDirectories(directory);
        Path marker = directory.resolve("RVN_COINS");
        if (!Files.exists(marker)) {
            try (var entries = Files.list(directory)) {
                if (entries.findAny().isPresent()) throw new IOException("Refusing foreign nonempty RVN coin directory");
            }
            try { Files.write(marker, identity, StandardOpenOption.CREATE_NEW); }
            catch (FileAlreadyExistsException race) { /* identity is checked below */ }
        }
        if (!Arrays.equals(identity, Files.readAllBytes(marker))) throw new IOException("RVN coin directory identity mismatch");
        if (!Files.exists(directory.resolve("CURRENT"))) {
            try (var entries = Files.list(directory)) {
                if (entries.anyMatch(path -> !path.getFileName().toString().equals("RVN_COINS")))
                    throw new IOException("RVN coin files exist without CURRENT; refusing reset");
            }
        }
    }
}
