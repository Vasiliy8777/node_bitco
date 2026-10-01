package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.protocol.transaction.OutPoint;

import java.util.Optional;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

public interface UtxoStore {

    void save(
            OutPoint outPoint,
            StoredUtxo utxo
    );

    Optional<StoredUtxo> find(
            OutPoint outPoint
    );

    /**
     * Resolves a group of UTXOs. Implementations backed by a database may override
     * this method with a native batch read; the default preserves compatibility
     * with lightweight/in-memory stores.
     */
    default Map<OutPoint, Optional<StoredUtxo>> findAll(
            Collection<OutPoint> outPoints
    ) {
        if (outPoints == null) {
            throw new IllegalArgumentException("outPoints must not be null");
        }

        Map<OutPoint, Optional<StoredUtxo>> result = new LinkedHashMap<>();
        for (OutPoint outPoint : outPoints) {
            if (outPoint == null) {
                throw new IllegalArgumentException("outPoints must not contain null");
            }
            result.putIfAbsent(outPoint, find(outPoint));
        }
        return result;
    }

    void delete(
            OutPoint outPoint
    );
}
