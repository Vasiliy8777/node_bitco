package ru.bitcoin.node.app.rpc;

import java.util.*;

/** Ordered diagnostics produced by the shared signing pass, as in SignatureData. */
final class PsbtMissing {
    final List<String> publicKeys = new ArrayList<>(), signatures = new ArrayList<>();
    byte[] redeemScript, witnessScript;
    void copyFrom(PsbtMissing source) {
        publicKeys.addAll(source.publicKeys); signatures.addAll(source.signatures);
        redeemScript = source.redeemScript; witnessScript = source.witnessScript;
    }
    boolean onlySignatures() {
        return publicKeys.isEmpty() && !nonzero(redeemScript) && !nonzero(witnessScript) && !signatures.isEmpty();
    }
    Map<String,Object> json() {
        var result = new LinkedHashMap<String,Object>();
        if (!publicKeys.isEmpty()) result.put("pubkeys",publicKeys);
        if (nonzero(redeemScript)) result.put("redeemscript",HexFormat.of().formatHex(redeemScript));
        if (nonzero(witnessScript)) result.put("witnessscript",HexFormat.of().formatHex(witnessScript));
        if (!signatures.isEmpty()) result.put("signatures",signatures);
        return result;
    }
    private static boolean nonzero(byte[] bytes) { if (bytes != null) for (byte b : bytes) if (b != 0) return true; return false; }
}
