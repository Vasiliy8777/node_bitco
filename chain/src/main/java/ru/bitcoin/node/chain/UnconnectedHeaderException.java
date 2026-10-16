package ru.bitcoin.node.chain;

import ru.bitcoin.node.common.types.Hash256;
import java.util.Objects;

/** An incoming header cannot connect to the known header tree. */
public final class UnconnectedHeaderException extends IllegalStateException {
    private final Hash256 headerHash;
    private final Hash256 parentHash;

    public UnconnectedHeaderException(Hash256 headerHash, Hash256 parentHash) {
        super("Parent header is unknown: " + Objects.requireNonNull(parentHash, "parentHash").toDisplayHex());
        this.headerHash = Objects.requireNonNull(headerHash, "headerHash");
        this.parentHash = parentHash;
    }

    public Hash256 headerHash() { return headerHash; }
    public Hash256 parentHash() { return parentHash; }
}
