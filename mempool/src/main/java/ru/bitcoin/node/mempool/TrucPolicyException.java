package ru.bitcoin.node.mempool;

import ru.bitcoin.node.protocol.transaction.Transaction;

/** Preserve local rejection identifiers while exposing Core's precise TRUC state. */
final class TrucPolicyException extends MempoolAdmissionException {
    private final String details;
    TrucPolicyException(Transaction child, Transaction parent) {
        super("truc-version-inheritance");
        details = "TRUC-violation, " + (child.version() == 3 ? "version=3" : "non-version=3")
                + " tx " + child.txId().toDisplayHex() + " (wtxid=" + child.wtxId().toDisplayHex() + ") cannot spend from "
                + (parent.version() == 3 ? "version=3" : "non-version=3") + " tx " + parent.txId().toDisplayHex()
                + " (wtxid=" + parent.wtxId().toDisplayHex() + ")";
    }
    String rpcDetails() { return details; }
}
