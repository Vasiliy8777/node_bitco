package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Stages and cryptographically verifies an AssumeUTXO snapshot without mutating active chainstate.
 */
public final class UtxoSnapshotVerifier {
    private final UtxoSnapshotStager stager;
    private final RocksDbSnapshotStagingStore store;
    private final NetworkParameters parameters;

    public UtxoSnapshotVerifier(RocksDbDatabase db, NetworkParameters parameters) {
        Objects.requireNonNull(db, "db");
        this.parameters = Objects.requireNonNull(parameters, "parameters");
        this.stager = new UtxoSnapshotStager(db);
        this.store = new RocksDbSnapshotStagingStore(db);
    }

    public record Verified(NetworkParameters.AssumeUtxoData trusted, long coinsLoaded,
                           RocksDbUtxoStore.Statistics statistics) {
    }

    public Verified stageAndVerify(Path path, NetworkParameters.AssumeUtxoData trusted) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(trusted, "trusted");
        if (!parameters.assumeUtxoData().contains(trusted)) throw new IOException("Untrusted AssumeUTXO metadata");
        try {
            var staged = stager.stage(path, parameters.magic(), trusted.height());
            if (!staged.baseHash().equals(trusted.blockHash()))
                throw new IOException("Snapshot base block does not match trusted AssumeUTXO block");
            var stats = store.statistics(RocksDbUtxoStore.HashType.HASH_SERIALIZED_3);
            if (stats.txouts() != staged.coinsLoaded())
                throw new IOException("Snapshot UTXO count changed during verification");
            if (!trusted.hashSerialized().equals(stats.hashSerialized3()))
                throw new IOException("Snapshot hash_serialized_3 does not match trusted AssumeUTXO commitment");
            return new Verified(trusted, staged.coinsLoaded(), stats);
        } catch (IOException | RuntimeException e) {
            stager.clear();
            throw e;
        }
    }

    public void recoverIncomplete() {
        stager.recoverIncomplete();
    }

    public void clear() {
        stager.clear();
    }
}
