package ru.bitcoin.node.storage.coinstats;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.hash.MuHash3072;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.rocksdb.RocksDbNamespaces;
import ru.bitcoin.node.storage.rocksdb.RocksDbWriteBatch;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Optional;

/** Persistent coinstatsindex records, active sync state and per-txid live-output counters. */
public final class RocksDbCoinStatsIndexStore {
    private static final byte RECORD_PREFIX = RocksDbNamespaces.COINSTATS_INDEX;
    private static final byte STATE_PREFIX = RocksDbNamespaces.COINSTATS_INDEX_STATE;
    private static final byte COUNT_PREFIX = RocksDbNamespaces.COINSTATS_TXOUT_COUNT;
    private static final byte[] STATE_KEY = {STATE_PREFIX};
    private static final int RECORD_SIZE = 8 + 32 + 8 + 8 + 8 + 8 + 32;
    private static final int STATE_SIZE = RECORD_SIZE + MuHash3072.SERIALIZED_STATE_SIZE;

    private final RocksDbDatabase database;

    public RocksDbCoinStatsIndexStore(RocksDbDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    public Optional<CoinStats> find(Hash256 blockHash) {
        Objects.requireNonNull(blockHash, "blockHash");
        byte[] value = database.get(recordKey(blockHash));
        return value == null ? Optional.empty() : Optional.of(readStats(value));
    }

    public Optional<ActiveState> activeState() {
        byte[] value = database.get(STATE_KEY);
        if (value == null) return Optional.empty();
        if (value.length != STATE_SIZE) throw new IllegalStateException("Invalid coinstatsindex state length: " + value.length);
        ByteBuffer in = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
        CoinStats stats = readStats(in);
        byte[] muhashState = new byte[MuHash3072.SERIALIZED_STATE_SIZE];
        in.get(muhashState);
        return Optional.of(new ActiveState(stats, MuHash3072.fromSerializedState(muhashState)));
    }

    public int liveOutputCount(Hash256 txid) {
        byte[] value = database.get(countKey(txid));
        if (value == null) return 0;
        if (value.length != 4) throw new IllegalStateException("Invalid coinstatsindex txout-count length: " + value.length);
        int count = ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (count <= 0) throw new IllegalStateException("Invalid coinstatsindex live-output count: " + count);
        return count;
    }

    public void initialize(Hash256 genesisHash) {
        Objects.requireNonNull(genesisHash, "genesisHash");
        clear();
        MuHash3072 muhash = new MuHash3072();
        CoinStats genesis = new CoinStats(0L, genesisHash, 0L, 0L, 0L, 0L, muhash.finalizeHash());
        try (var batch = new RocksDbWriteBatch()) {
            commit(genesis, muhash, batch);
        }
    }

    public void commit(CoinStats stats, MuHash3072 muhash, RocksDbWriteBatch batch) {
        Objects.requireNonNull(stats, "stats");
        Objects.requireNonNull(muhash, "muhash");
        Objects.requireNonNull(batch, "batch");
        batch.put(recordKey(stats.blockHash()), serializeStats(stats));
        ByteBuffer state = ByteBuffer.allocate(STATE_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        writeStats(state, stats);
        state.put(muhash.serializeState());
        batch.put(STATE_KEY, state.array());
        database.write(batch);
    }

    public void setLiveOutputCount(RocksDbWriteBatch batch, Hash256 txid, int count) {
        Objects.requireNonNull(batch, "batch");
        Objects.requireNonNull(txid, "txid");
        if (count < 0) throw new IllegalArgumentException("count must not be negative");
        byte[] key = countKey(txid);
        if (count == 0) batch.delete(key);
        else batch.put(key, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(count).array());
    }

    public void clear() {
        try (var batch = new RocksDbWriteBatch()) {
            batch.deletePrefix(RECORD_PREFIX);
            batch.deletePrefix(STATE_PREFIX);
            batch.deletePrefix(COUNT_PREFIX);
            database.write(batch);
        }
    }

    private static byte[] recordKey(Hash256 hash) {
        byte[] bytes = hash.bytes();
        byte[] key = new byte[1 + bytes.length];
        key[0] = RECORD_PREFIX;
        System.arraycopy(bytes, 0, key, 1, bytes.length);
        return key;
    }

    private static byte[] countKey(Hash256 txid) {
        byte[] bytes = txid.bytes();
        byte[] key = new byte[1 + bytes.length];
        key[0] = COUNT_PREFIX;
        System.arraycopy(bytes, 0, key, 1, bytes.length);
        return key;
    }

    private static byte[] serializeStats(CoinStats stats) {
        ByteBuffer out = ByteBuffer.allocate(RECORD_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        writeStats(out, stats);
        return out.array();
    }

    private static CoinStats readStats(byte[] value) {
        if (value.length != RECORD_SIZE) throw new IllegalStateException("Invalid coinstatsindex record length: " + value.length);
        return readStats(ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN));
    }

    private static CoinStats readStats(ByteBuffer in) {
        long height = in.getLong();
        byte[] hash = new byte[32]; in.get(hash);
        long transactions = in.getLong();
        long txouts = in.getLong();
        long bogoSize = in.getLong();
        long totalAmount = in.getLong();
        byte[] muhash = new byte[32]; in.get(muhash);
        return new CoinStats(height, new Hash256(hash), transactions, txouts, bogoSize, totalAmount, new Hash256(muhash));
    }

    private static void writeStats(ByteBuffer out, CoinStats stats) {
        out.putLong(stats.height());
        out.put(stats.blockHash().bytes());
        out.putLong(stats.transactions());
        out.putLong(stats.txouts());
        out.putLong(stats.bogoSize());
        out.putLong(stats.totalAmount());
        out.put(stats.muhash().bytes());
    }

    public record CoinStats(long height, Hash256 blockHash, long transactions, long txouts,
                            long bogoSize, long totalAmount, Hash256 muhash) {
        public CoinStats {
            Objects.requireNonNull(blockHash, "blockHash");
            Objects.requireNonNull(muhash, "muhash");
            if (height < 0 || transactions < 0 || txouts < 0 || bogoSize < 0 || totalAmount < 0)
                throw new IllegalArgumentException("Coin statistics must not be negative");
        }
    }

    public record ActiveState(CoinStats stats, MuHash3072 muhash) {
        public ActiveState {
            Objects.requireNonNull(stats, "stats");
            Objects.requireNonNull(muhash, "muhash");
        }
    }
}
