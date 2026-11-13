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
    private TrucPolicyException(String diagnostic,String detail) {super(diagnostic);details="TRUC-violation, "+detail;}
    static TrucPolicyException size(MempoolEntry entry,boolean child) {
        return new TrucPolicyException(child?"truc-child-size":"truc-topology",
                "version=3 "+(child?"child ":"")+label(entry.transaction())+" is too big: "+entry.virtualSize()
                        +" > "+(child?1000:10000)+" virtual bytes");
    }
    static TrucPolicyException ancestors(Transaction tx) {
        return new TrucPolicyException("truc-topology",label(tx)+" would have too many ancestors");
    }
    static TrucPolicyException descendants(Transaction tx) {
        return new TrucPolicyException("truc-topology",label(tx)+" would exceed descendant count limit");
    }
    private static String label(Transaction tx) {return "tx "+tx.txId().toDisplayHex()+" (wtxid="+tx.wtxId().toDisplayHex()+")";}
}
