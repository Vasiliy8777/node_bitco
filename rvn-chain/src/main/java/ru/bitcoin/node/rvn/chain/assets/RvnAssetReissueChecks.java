package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import java.util.function.Function;
import ru.bitcoin.node.rvn.chain.RvnPayoutAddress;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.COIN;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.*;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetIssueChecks.Output;

/** Canonical mainnet root/sub/restricted reissue authorization plan. Signatures,
 * native accounting, block-wide reissue chaining and historical scripts are separate. */
public final class RvnAssetReissueChecks {
    private RvnAssetReissueChecks() {}

    public static Metadata verifyMainnet(List<? extends Payload> spent, List<Output> outputs,
                                         Activation activation, Function<String, Metadata> state) {
        return verifyMainnet(spent, outputs, activation, state, RvnAssetScriptCodec::decodeCanonicalSpendable);
    }
    static Metadata verifyMainnet(List<? extends Payload> spent, List<Output> outputs,
                                  Activation activation, Function<String, Metadata> state,
                                  Function<byte[], Optional<Decoded>> decoder) {
        Objects.requireNonNull(state);
        if (outputs.size() < 3) throw new SecurityException("RVN reissue requires burn/owner/reissue outputs");
        var decoded = outputs.stream().map(o -> decoder.apply(o.script()).map(Decoded::payload).orElse(null)).toList();
        if (!(decoded.get(decoded.size() - 1) instanceof Reissue reissue))
            throw new SecurityException("RVN reissue must be last output");
        var type = RvnAssetNames.requireValid(reissue.name());
        if (type != RvnAssetNames.Type.ROOT && type != RvnAssetNames.Type.SUB && type != RvnAssetNames.Type.RESTRICTED)
            throw new UnsupportedOperationException("Unsupported reissue family");
        var previous = state.apply(reissue.name());
        if (previous == null) throw new SecurityException("RVN reissue asset missing");
        var updated = reissue(reissue, previous, activation);
        var transfers = new ArrayList<Payload>();
        for (int i = 0; i < decoded.size() - 1; i++) {
            var payload = decoded.get(i);
            if (payload == null) continue;
            if (!(payload instanceof Transfer)) throw new SecurityException("Unexpected reissue asset output");
            transfers.add(payload);
        }
        String owner = (type == RvnAssetNames.Type.RESTRICTED ? reissue.name().substring(1) : reissue.name()) + "!";
        if (transfers.stream().noneMatch(p -> p.name().equals(owner)))
            throw new SecurityException("RVN reissue requires owner return");
        byte[] burn = RvnPayoutAddress.parse("RXReissueAssetXXXXXXXXXXXXXXVEFAWu", RvnNetwork.MAINNET).scriptPubKey();
        if (outputs.stream().noneMatch(o -> o.value() == 100 * COIN && Arrays.equals(o.script(), burn)))
            throw new SecurityException("RVN reissue burn output missing or incorrect");
        // Minted amount resides solely in the final reissue output. All transfer
        // outputs still conserve existing inputs using the pre-reissue units.
        RvnAssetTransferChecks.verify(spent, transfers, state, activation);
        return updated;
    }
}
