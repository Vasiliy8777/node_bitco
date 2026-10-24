package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import java.util.function.Function;
import ru.bitcoin.node.rvn.chain.RvnPayoutAddress;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.COIN;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.*;

/** Canonical mainnet root/sub issuance plan. Does not verify native coins,
 * signatures, historical script classification or persist the resulting metadata. */
public final class RvnAssetIssueChecks {
    private RvnAssetIssueChecks() {}
    public record Output(long value, byte[] script) {
        public Output { script = script.clone(); }
        @Override public byte[] script() { return script.clone(); }
    }

    public static Metadata verifyMainnet(List<? extends Payload> spent, List<Output> outputs,
                                         Activation activation, Function<String, Metadata> state) {
        return verifyMainnet(spent, outputs, activation, state, RvnAssetScriptCodec::decodeCanonicalSpendable);
    }
    static Metadata verifyMainnet(List<? extends Payload> spent, List<Output> outputs,
                                  Activation activation, Function<String, Metadata> state,
                                  Function<byte[], Optional<Decoded>> decoder) {
        Objects.requireNonNull(state);
        if (outputs.size() < 3) throw new SecurityException("RVN issuance requires burn/owner/issue outputs");
        var decoded = outputs.stream().map(o -> decoder.apply(o.script()).map(Decoded::payload).orElse(null)).toList();
        if (!(decoded.get(decoded.size() - 1) instanceof Issue issue)
                || !(decoded.get(decoded.size() - 2) instanceof Owner owner)
                || !owner.name().equals(issue.name() + "!"))
            throw new SecurityException("RVN issuance requires matching final owner/issue pair");
        var type = RvnAssetNames.requireValid(issue.name());
        if (type != RvnAssetNames.Type.ROOT && type != RvnAssetNames.Type.SUB)
            throw new UnsupportedOperationException("Only ordinary root/sub issuance supported");
        var updated = issue(issue, activation, n -> state.apply(n) != null);
        var transfers = new ArrayList<Payload>();
        for (int i = 0; i < decoded.size() - 2; i++) {
            var payload = decoded.get(i);
            if (payload == null) continue;
            if (!(payload instanceof Transfer)) throw new SecurityException("Unexpected issuance asset output");
            transfers.add(payload);
        }
        boolean sub = type == RvnAssetNames.Type.SUB;
        String burnAddress = sub ? "RXissueSubAssetXXXXXXXXXXXXXWcwhwL" : "RXissueAssetXXXXXXXXXXXXXXXXXhhZGt";
        byte[] burnScript = RvnPayoutAddress.parse(burnAddress, RvnNetwork.MAINNET).scriptPubKey();
        long burn = (sub ? 100 : 500) * COIN;
        if (outputs.stream().noneMatch(o -> o.value() == burn && Arrays.equals(o.script(), burnScript)))
            throw new SecurityException("RVN issuance burn output missing or incorrect");
        if (sub) {
            String parentOwner = issue.name().substring(0, issue.name().lastIndexOf('/')) + "!";
            if (transfers.stream().noneMatch(p -> p.name().equals(parentOwner)))
                throw new SecurityException("RVN subasset requires parent owner return");
        }
        RvnAssetTransferChecks.verify(spent, transfers, state, activation);
        return updated;
    }
}
