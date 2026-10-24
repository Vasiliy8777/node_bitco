package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import java.util.function.Function;
import ru.bitcoin.node.rvn.chain.RvnPayoutAddress;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.COIN;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.*;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetIssueChecks.Output;

/** Canonical mainnet unique batch / message-channel issue authorization.
 * Native values, signatures and historical envelopes are checked separately. */
public final class RvnTaggedAssetIssueChecks {
    private RvnTaggedAssetIssueChecks() {}

    public static Map<String, Metadata> verifyMainnet(List<? extends Payload> spent, List<Output> outputs,
                                                     Activation activation, Function<String, Metadata> state) {
        return verifyMainnet(spent, outputs, activation, state, RvnAssetScriptCodec::decodeCanonicalSpendable);
    }
    static Map<String, Metadata> verifyMainnet(List<? extends Payload> spent, List<Output> outputs,
                                              Activation activation, Function<String, Metadata> state,
                                              Function<byte[], Optional<Decoded>> decoder) {
        return verifyMainnet(spent, outputs, activation, state, decoder, true);
    }
    static Map<String, Metadata> verifyMainnet(List<? extends Payload> spent, List<Output> outputs,
            Activation activation, Function<String, Metadata> state,
            Function<byte[], Optional<Decoded>> decoder, boolean contextual) {
        Objects.requireNonNull(state);
        if (outputs.size() < 3) throw new SecurityException("RVN tagged issue requires burn/owner/issue outputs");
        var decoded = outputs.stream().map(o -> decoder.apply(o.script()).map(Decoded::payload).orElse(null)).toList();
        if (!(decoded.getLast() instanceof Issue last)) throw new SecurityException("RVN tagged issue must end with issuance");
        var type = RvnAssetNames.requireValid(last.name());
        boolean unique = type == RvnAssetNames.Type.UNIQUE;
        if (!unique && type != RvnAssetNames.Type.CHANNEL)
            throw new UnsupportedOperationException("Expected unique/channel issue");
        String parent = parent(last.name(), unique);
        var issued = new LinkedHashMap<String, Metadata>();
        var transfers = new ArrayList<Payload>();
        for (var payload : decoded) {
            if (payload == null) continue;
            if (payload instanceof Issue issue) {
                if (RvnAssetNames.requireValid(issue.name()) != type || !parent.equals(parent(issue.name(), unique)))
                    throw new SecurityException("RVN tagged issue mixes families or parents");
                var value = issue(issue, activation, n -> (contextual && state.apply(n) != null) || issued.containsKey(n));
                issued.put(value.name(), value);
            } else if (payload instanceof Transfer) transfers.add(payload);
            else throw new SecurityException("Unexpected owner creation/reissue in tagged issuance");
        }
        if (!unique && issued.size() != 1) throw new SecurityException("RVN channel transaction issues exactly one asset");
        String owner = parent + "!";
        if (transfers.stream().noneMatch(p -> p.name().equals(owner)))
            throw new SecurityException("RVN tagged issue requires parent owner return");
        byte[] burnScript = RvnPayoutAddress.parse(unique ? "RXissueUniqueAssetXXXXXXXXXXWEAe58"
                : "RXissueMsgChanneLAssetXXXXXXSjHvAY", RvnNetwork.MAINNET).scriptPubKey();
        long burn = Math.multiplyExact((unique ? 5L * issued.size() : 100L), COIN);
        if (outputs.stream().noneMatch(o -> o.value() == burn && Arrays.equals(o.script(), burnScript)))
            throw new SecurityException("RVN tagged issue requires exact single burn output");
        if (contextual) RvnAssetTransferChecks.verify(spent, transfers, state, activation);
        return Map.copyOf(issued);
    }

    private static String parent(String name, boolean unique) {
        int split = name.indexOf(unique ? '#' : '~');
        if (split < 1) throw new SecurityException("Invalid RVN tagged asset parent");
        return name.substring(0, split);
    }
}
