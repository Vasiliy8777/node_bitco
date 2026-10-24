package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import java.util.function.Function;
import ru.bitcoin.node.rvn.chain.RvnPayoutAddress;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.COIN;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.*;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetIssueChecks.Output;

/** Restricted issuance layout/burn/ownership. Verifier and address rules are
 * mandatory separate checks in RvnRestrictedBlockState. */
public final class RvnRestrictedIssueChecks {
    private RvnRestrictedIssueChecks() {}
    public static Metadata verifyMainnet(List<? extends Payload> spent, List<Output> outputs,
                                         Activation activation, Function<String, Metadata> state) {
        if (outputs.size() < 4) throw new SecurityException("RVN restricted issuance requires four outputs");
        var decoded = outputs.stream().map(o -> decodeCanonicalSpendable(o.script()).map(Decoded::payload).orElse(null)).toList();
        if (!(decoded.getLast() instanceof Issue issue) || RvnAssetNames.requireValid(issue.name()) != RvnAssetNames.Type.RESTRICTED)
            throw new SecurityException("RVN restricted issuance must be last");
        var metadata = issue(issue, activation, name -> state.apply(name) != null);
        var transfers = new ArrayList<Payload>();
        for (int i = 0; i < decoded.size() - 1; i++) {
            var payload = decoded.get(i);
            if (payload == null) continue;
            if (!(payload instanceof Transfer)) throw new SecurityException("Unexpected restricted issuance asset output");
            transfers.add(payload);
        }
        String owner = issue.name().substring(1) + "!";
        if (transfers.stream().noneMatch(p -> p.name().equals(owner))) throw new SecurityException("RVN restricted root owner missing");
        long verifiers = outputs.stream().map(o -> RvnHistoricalAssetCodec.decodeNull(o.script()))
                .filter(o -> o.isPresent() && o.get().kind() == RvnHistoricalAssetCodec.NullKind.VERIFIER).count();
        if (verifiers != 1) throw new SecurityException("RVN restricted issuance requires one verifier");
        byte[] burn = RvnPayoutAddress.parse("RXissueRestrictedXXXXXXXXXXXXzJZ1q", RvnNetwork.MAINNET).scriptPubKey();
        if (outputs.stream().noneMatch(o -> o.value() == 1500 * COIN && Arrays.equals(o.script(), burn)))
            throw new SecurityException("RVN restricted issuance burn missing");
        RvnAssetTransferChecks.verify(spent, transfers, state, activation);
        return metadata;
    }
}
