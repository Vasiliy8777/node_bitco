package ru.bitcoin.node.chain.utxo;

import ru.bitcoin.node.consensus.transaction.UtxoEntry;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.storage.utxo.StoredUtxo;

import java.util.Optional;

public final class UtxoOverlayView
        implements ru.bitcoin.node.consensus.transaction.UtxoView {

    private final UtxoOverlay overlay;

    public UtxoOverlayView(
            UtxoOverlay overlay
    ) {
        if (overlay == null) {
            throw new IllegalArgumentException(
                    "overlay must not be null"
            );
        }

        this.overlay = overlay;
    }

    @Override
    public Optional<UtxoEntry> find(
            OutPoint outPoint
    ) {
        return overlay.find(
                        outPoint
                )
                .map(
                        UtxoOverlayView::toEntry
                );
    }

    private static UtxoEntry toEntry(
            StoredUtxo utxo
    ) {
        return new UtxoEntry(
                utxo.amount(),
                utxo.scriptPubKey(),
                utxo.height(),
                utxo.coinbase()
        );
    }
}