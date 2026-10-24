package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import java.util.function.Function;
import ru.bitcoin.node.rvn.chain.RvnPayoutAddress;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.COIN;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetRuleKey.Kind.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.*;
import ru.bitcoin.node.rvn.chain.assets.RvnAssetIssueChecks.Output;

/** Block-local rule plans with parent-only restricted transfer checks. This
 * canonical path retains explicit unsupported historical cases; not replay certification. */
final class RvnRestrictedBlockState {
    private final Function<byte[], Optional<Decoded>> decoder;
    private final RvnAssetRulesView parent;
    private final Function<String, Metadata> registry;
    private final Map<RvnAssetRuleKey, RvnAssetChanges.RuleUpdate> changes = new HashMap<>();
    RvnRestrictedBlockState(RvnAssetRulesView parent, Function<String, Metadata> registry,
                            Function<byte[], Optional<Decoded>> decoder) {
        this.decoder = Objects.requireNonNull(decoder);
        this.parent = Objects.requireNonNull(parent); this.registry = Objects.requireNonNull(registry);
    }
    Map<RvnAssetRuleKey, RvnAssetChanges.RuleUpdate> changes() { return Map.copyOf(changes); }
    void source(Payload payload, byte[] script) {
        if (RvnAssetNames.requireValid(payload.name()) == RvnAssetNames.Type.RESTRICTED)
            RvnRestrictedRuleChecks.requireSource(parent, payload.name(), address(script));
    }
    void transaction(List<Output> outputs, Activation activation, boolean coinbase) {
        var nulls = new ArrayList<RvnHistoricalAssetCodec.NullData>();
        var transfers = new HashSet<String>();
        var restrictedCreations = new ArrayList<Payload>();
        String verifier = null;
        for (var output : outputs) {
            var data = RvnHistoricalAssetCodec.decodeNull(output.script());
            if (data.isPresent()) {
                if (!activation.restricted() || coinbase) throw new SecurityException("RVN null data before activation/in coinbase");
                if (data.get().kind() == RvnHistoricalAssetCodec.NullKind.VERIFIER) {
                    if (verifier != null) throw new SecurityException("RVN duplicate verifier in transaction");
                    verifier = data.get().verifier();
                    RvnRestrictedRuleChecks.requireVerifier(parent, verifier, null, q -> registry.apply(q) != null);
                } else nulls.add(data.get());
                continue;
            }
            var asset = decoder.apply(output.script());
            if (asset.isEmpty()) continue;
            var payload = asset.get().payload();
            if (payload instanceof Transfer) transfers.add(payload.name());
            if (RvnAssetNames.requireValid(payload.name()) != RvnAssetNames.Type.RESTRICTED) continue;
            if (!activation.restricted()) throw new SecurityException("RVN restricted assets before activation");
            if (payload instanceof Transfer)
                RvnRestrictedRuleChecks.requireDestination(parent, payload.name(), address(output.script()), q -> registry.apply(q) != null);
            else restrictedCreations.add(payload);
        }
        if (verifier != null && restrictedCreations.size() != 1)
            throw new SecurityException("RVN verifier must belong to one restricted issuance/reissue");
        for (var creation : restrictedCreations) {
            boolean issue = creation instanceof Issue;
            if (issue && verifier == null) throw new SecurityException("RVN restricted issuance verifier missing");
            var key = new RvnAssetRuleKey(VERIFIER, creation.name(), "");
            String current = changes.containsKey(key) ? changes.get(key).after().orElse(null) : parent.findRule(key).orElse(null);
            String expression = verifier != null ? verifier : current;
            long amount = issue ? ((Issue) creation).amount() : ((Reissue) creation).amount();
            if (issue || amount > 0) {
                if (expression == null) throw new SecurityException("RVN restricted verifier missing");
                for (var output : outputs) {
                    var asset = decoder.apply(output.script());
                    if (asset.isPresent() && asset.get().payload().equals(creation))
                        RvnRestrictedRuleChecks.requireVerifier(parent, expression, address(output.script()), q -> registry.apply(q) != null);
                }
            }
            if (verifier != null) put(key, Optional.of(verifier));
        }
        var touched = new HashSet<RvnAssetRuleKey>(); int tags = 0;
        for (var data : nulls) {
            var type = RvnAssetNames.requireValid(data.name());
            boolean qualifier = type == RvnAssetNames.Type.QUALIFIER || type == RvnAssetNames.Type.SUB_QUALIFIER;
            RvnAssetRuleKey.Kind kind = data.kind() == RvnHistoricalAssetCodec.NullKind.GLOBAL ? GLOBAL_FREEZE
                    : qualifier ? QUALIFIER : ADDRESS_FREEZE;
            var key = new RvnAssetRuleKey(kind, data.name(), data.addressHash());
            if (!touched.add(key)) throw new SecurityException("RVN repeated address/global rule in transaction");
            String authorization = qualifier ? data.name() : data.name().substring(1) + "!";
            if (!transfers.contains(authorization)) throw new SecurityException("RVN rule change lacks authorizing transfer");
            var update = RvnRestrictedRuleChecks.toggle(parent, key, data.flag());
            changes.put(key, update);
            if (qualifier && data.flag() == 1) tags++;
        }
        if (tags > 0) {
            long burnAmount = Math.multiplyExact(COIN / 10, tags);
            byte[] burn = RvnPayoutAddress.parse("RXaddTagBurnXXXXXXXXXXXXXXXXZQm5ya", RvnNetwork.MAINNET).scriptPubKey();
            if (outputs.stream().noneMatch(o -> o.value() == burnAmount && Arrays.equals(o.script(), burn)))
                throw new SecurityException("RVN qualifier tagging burn missing");
        }
    }
    private void put(RvnAssetRuleKey key, Optional<String> value) {
        var before = parent.findRule(key);
        if (before.equals(value)) changes.remove(key);
        else changes.put(key, new RvnAssetChanges.RuleUpdate(before, value));
    }
    private static String address(byte[] script) {
        // Core Solver extracts these address bytes for positional asset scripts too.
        return HexFormat.of().formatHex(Arrays.copyOfRange(script, 3, 23));
    }
}
