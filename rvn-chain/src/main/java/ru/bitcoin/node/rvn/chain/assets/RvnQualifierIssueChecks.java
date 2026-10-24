package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import java.util.function.Function;
import ru.bitcoin.node.rvn.chain.RvnPayoutAddress;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.COIN;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.*;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetIssueChecks.Output;

/** Canonical qualifier/subqualifier issuance. Does not process address tags. */
public final class RvnQualifierIssueChecks {
    private RvnQualifierIssueChecks() {}
    public static Metadata verifyMainnet(List<? extends Payload> spent, List<Output> outputs,
                                         Activation activation, Function<String, Metadata> state) {
        return verifyMainnet(spent, outputs, activation, state, RvnAssetScriptCodec::decodeCanonicalSpendable);
    }
    static Metadata verifyMainnet(List<? extends Payload> spent, List<Output> outputs,
                                  Activation activation, Function<String, Metadata> state,
                                  Function<byte[], Optional<Decoded>> decoder) {
        if (outputs.size() < 2) throw new SecurityException("RVN qualifier issue requires burn and issuance");
        var decoded = outputs.stream().map(o -> decoder.apply(o.script()).map(Decoded::payload).orElse(null)).toList();
        if (!(decoded.getLast() instanceof Issue issue)) throw new SecurityException("RVN qualifier issuance must be last");
        var type = RvnAssetNames.requireValid(issue.name());
        if (type != RvnAssetNames.Type.QUALIFIER && type != RvnAssetNames.Type.SUB_QUALIFIER)
            throw new UnsupportedOperationException("Expected qualifier/subqualifier issuance");
        var result = issue(issue, activation, name -> state.apply(name) != null);
        var transfers = new ArrayList<Payload>();
        for (int i = 0; i < decoded.size() - 1; i++) {
            var payload = decoded.get(i);
            if (payload == null) continue;
            if (!(payload instanceof Transfer)) throw new SecurityException("Unexpected qualifier issuance asset output");
            transfers.add(payload);
        }
        boolean sub = type == RvnAssetNames.Type.SUB_QUALIFIER;
        byte[] burn = RvnPayoutAddress.parse(sub ? "RXissueSubQuaLifierXXXXXXXXXVTzvv5" : "RXissueQuaLifierXXXXXXXXXXXXUgEDbC",
                RvnNetwork.MAINNET).scriptPubKey();
        long amount = (sub ? 100 : 1000) * COIN;
        if (outputs.stream().noneMatch(o -> o.value() == amount && Arrays.equals(o.script(), burn)))
            throw new SecurityException("RVN qualifier burn output missing");
        if (sub) {
            String parent = issue.name().substring(0, issue.name().indexOf('/'));
            if (transfers.stream().noneMatch(p -> p.name().equals(parent)))
                throw new SecurityException("RVN subqualifier requires parent qualifier return");
        }
        RvnAssetTransferChecks.verify(spent, transfers, state, activation);
        return result;
    }
}
