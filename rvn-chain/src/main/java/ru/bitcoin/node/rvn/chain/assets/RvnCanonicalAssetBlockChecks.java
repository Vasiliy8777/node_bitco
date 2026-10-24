package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import java.util.function.Function;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.chain.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetMetadataRules.*;

/** Explicit canonical mainnet asset block validator (vote assets excluded). NOT full historical
 * asset consensus: rejects unsupported families/envelopes rather than admitting
 * them as native transactions. Header/PoW/deployment verification is external.
 * Returned plans can be committed atomically by RvnCoinStore; no state mutates here. */
public final class RvnCanonicalAssetBlockChecks {
    private RvnCanonicalAssetBlockChecks() {}
    public record Plan(RvnCoinAccounting.Changes coins, RvnAssetChanges assets) {}

    /** CheckTransaction still runs for coinbase before initial asset deployment;
     * AddCoins does not yet apply any asset metadata/rules in that era. */
    public static void requireBeforeAssets(Transaction tx) {
        Function<byte[], Optional<Decoded>> decoder = script -> RvnHistoricalAssetCodec.decodeSpendable(script, false, false);
        var outputs = new ArrayList<RvnAssetIssueChecks.Output>(); var payloads = new ArrayList<Payload>();
        for (var output : tx.outputs()) {
            byte[] script = output.scriptPubKey(); var payload = classify(script, decoder);
            if (!tx.isCoinbase() && (payload != null || RvnHistoricalAssetCodec.decodeNull(script).isPresent()))
                throw new SecurityException("RVN asset transaction before activation");
            if (payload != null) {
                if (!(payload instanceof Reissue) && output.value() != 0)
                    throw new SecurityException("Nonzero native value in RVN asset output");
                payloads.add(payload);
            }
            outputs.add(new RvnAssetIssueChecks.Output(output.value(), script));
        }
        if (tx.isCoinbase()) {
            var empty = new RvnAssetRulesView() {
                public Optional<String> findRule(RvnAssetRuleKey key) { return Optional.empty(); }
                public boolean hasQualifier(String name, String address) { return false; }
            };
            RvnHistoricalAssetCoinbaseChecks.verify(outputs, payloads, name -> null, decoder);
            new RvnRestrictedBlockState(empty, name -> null, decoder).transaction(outputs, new Activation(false, false, false, false), true);
        }
    }

    public static Plan verifyMainnet(RvnBlock block, RvnSpendContext context, Activation activation,
                                    boolean enforcedReissueValues, RvnCoinView parent,
                                    Function<String, Metadata> registry) {
        RvnAssetRulesView rules = parent instanceof RvnAssetRulesView view ? view : new RvnAssetRulesView() {
            public Optional<String> findRule(RvnAssetRuleKey key) { return Optional.empty(); }
            public boolean hasQualifier(String name, String address) { return false; }
        };
        return verifyMainnet(block, context, activation, enforcedReissueValues, parent, registry, rules);
    }

    public static Plan verifyMainnet(RvnBlock block, RvnSpendContext context, Activation activation,
                                    boolean enforcedReissueValues, RvnCoinView parent,
                                    Function<String, Metadata> registry, RvnAssetRulesView parentRules) {
        return verify(block, context, activation, enforcedReissueValues, parent, registry, parentRules,
                RvnAssetScriptCodec::decodeCanonicalSpendable, true);
    }

    /** Historical envelopes use Core positional decoding. Unsupported consensus cases still fail closed. */
    public static Plan verifyHistoricalMainnet(RvnBlock block, RvnSpendContext context, Activation activation,
                                    boolean enforcedReissueValues, RvnCoinView parent,
                                    Function<String, Metadata> registry, RvnAssetRulesView parentRules,
                                    boolean transferSizeFix) {
        return verifyHistoricalMainnet(block, context, activation, enforcedReissueValues, parent, registry,
                parentRules, transferSizeFix, true);
    }

    public static Plan verifyHistoricalMainnet(RvnBlock block, RvnSpendContext context, Activation activation,
                                    boolean enforcedReissueValues, RvnCoinView parent,
                                    Function<String, Metadata> registry, RvnAssetRulesView parentRules,
                                    boolean transferSizeFix, boolean coinbaseAssetCheck) {
        return verify(block, context, activation, enforcedReissueValues, parent, registry, parentRules,
                script -> RvnHistoricalAssetCodec.decodeSpendable(script, transferSizeFix, activation.messages()), coinbaseAssetCheck);
    }

    private static Plan verify(RvnBlock block, RvnSpendContext context, Activation activation,
                                    boolean enforcedReissueValues, RvnCoinView parent,
                                    Function<String, Metadata> registry, RvnAssetRulesView parentRules,
                                    Function<byte[], Optional<Decoded>> decoder, boolean coinbaseAssetCheck) {
        if (!activation.assets()) throw new IllegalArgumentException("Canonical asset block checks require activation");
        Objects.requireNonNull(registry);
        RvnCoinbase.requireHeight(block.transactions().getFirst(), context.height());
        // Check native values, signatures (full asset scriptCode), locks, sigops,
        // coinbase reward and body commitments before publishing any state plan.
        var coinChanges = RvnNativeTransactionChecks.blockCanonicalAssets(block, context, parent, enforcedReissueValues);
        var overlay = new HashMap<OutPoint, RvnCoin>();
        var consumed = new HashSet<OutPoint>();
        var metadata = new HashMap<String, Metadata>();
        Function<String, Metadata> view = name -> metadata.containsKey(name) ? metadata.get(name) : registry.apply(name);
        var reissued = new HashSet<String>();
        var rules = new RvnRestrictedBlockState(parentRules, registry, decoder);
        for (var tx : block.transactions()) {
            if (tx.isCoinbase() && coinbaseAssetCheck)
                for (var output : tx.outputs())
                    if (RvnHistoricalAssetCodec.classify(output.scriptPubKey()).isPresent()
                            || RvnHistoricalAssetCodec.decodeNull(output.scriptPubKey()).isPresent())
                        throw new SecurityException("RVN coinbase contains assets after bit 10 activation");
            var spent = new ArrayList<Payload>();
            if (!tx.isCoinbase()) for (var input : tx.inputs()) {
                var point = input.previousOutput();
                if (!consumed.add(point)) throw new SecurityException("RVN asset block double spend");
                var coin = overlay.remove(point);
                if (coin == null) coin = parent.find(point).orElseThrow(() -> new SecurityException("RVN missing asset input"));
                var asset = classify(coin.output().scriptPubKey(), decoder);
                if (asset != null) { spent.add(asset); rules.source(asset, coin.output().scriptPubKey()); }
            }
            var outputs = new ArrayList<RvnAssetIssueChecks.Output>();
            var payloads = new ArrayList<Payload>();
            for (var output : tx.outputs()) {
                outputs.add(new RvnAssetIssueChecks.Output(output.value(), output.scriptPubKey()));
                var payload = classify(output.scriptPubKey(), decoder);
                if (payload == null) continue;
                if (!(payload instanceof Reissue) || enforcedReissueValues)
                    if (output.value() != 0) throw new SecurityException("Nonzero native value in RVN asset output");
                if (payload instanceof Transfer transfer && !tx.isCoinbase()) {
                    if (!activation.messages() && (transfer.message() != null || transfer.expires() != 0))
                        throw new UnsupportedOperationException("Pre-messaging transfer extensions require historical parsing");
                    if (activation.messages() && (transfer.expires() < 0 || (transfer.expires() > 0 && transfer.message() == null)))
                        throw new SecurityException("RVN transfer message expiration");
                }
                payloads.add(payload);
            }
            if (tx.isCoinbase()) {
                metadata.putAll(RvnHistoricalAssetCoinbaseChecks.verify(outputs, payloads, view, decoder));
                rules.transaction(outputs, activation, true);
                continue;
            }
            rules.transaction(outputs, activation, tx.isCoinbase());
            if (payloads.stream().anyMatch(p -> p instanceof Issue)) {
                var last = payloads.getLast();
                var type = RvnAssetNames.requireValid(last.name());
                if (last instanceof Issue && (type == RvnAssetNames.Type.UNIQUE || type == RvnAssetNames.Type.CHANNEL))
                    metadata.putAll(RvnTaggedAssetIssueChecks.verifyMainnet(spent, outputs, activation, view, decoder));
                else if (last instanceof Issue && (type == RvnAssetNames.Type.QUALIFIER || type == RvnAssetNames.Type.SUB_QUALIFIER)) {
                    var issued = RvnQualifierIssueChecks.verifyMainnet(spent, outputs, activation, view, decoder);
                    metadata.put(issued.name(), issued);
                }
                else if (last instanceof Issue && type == RvnAssetNames.Type.RESTRICTED) {
                    var issued = RvnRestrictedIssueChecks.verifyMainnet(spent, outputs, activation, view, decoder);
                    metadata.put(issued.name(), issued);
                }
                else {
                    var issued = RvnAssetIssueChecks.verifyMainnet(spent, outputs, activation, view, decoder);
                    metadata.put(issued.name(), issued);
                }
            } else if (payloads.stream().anyMatch(p -> p instanceof Reissue)) {
                var updated = RvnAssetReissueChecks.verifyMainnet(spent, outputs, activation, view, decoder);
                if (!reissued.add(updated.name())) throw new SecurityException("RVN multiple reissues of one asset in block");
                metadata.put(updated.name(), updated);
            } else {
                RvnAssetTransferChecks.verify(spent, payloads, view, activation);
            }
            if (!tx.isCoinbase()) for (int index = 0; index < tx.outputs().size(); index++) {
                var output = tx.outputs().get(index); byte[] script = output.scriptPubKey();
                if (!RvnScriptSemantics.unspendable(script))
                    overlay.put(new OutPoint(tx.txId(), new UInt32(index)), new RvnCoin(output, context.height(), false));
            }
        }
        var changes = new HashMap<String, RvnAssetChanges.Update>();
        for (var entry : metadata.entrySet()) {
            var before = Optional.ofNullable(registry.apply(entry.getKey()));
            var after = Optional.of(entry.getValue());
            if (!before.equals(after)) changes.put(entry.getKey(), new RvnAssetChanges.Update(before, after));
        }
        return new Plan(coinChanges, new RvnAssetChanges(changes, rules.changes()));
    }

    private static Payload classify(byte[] script, Function<byte[], Optional<Decoded>> decoder) {
        var decoded = decoder.apply(script);
        if (decoded.isPresent()) return decoded.get().payload();
        if (RvnHistoricalAssetCodec.decodeNull(script).isPresent()) return null;
        // Core permits opaque unclassified leading OP_RVN_ASSET scripts; they
        // are unspendable. Only misplaced unclassified opcodes are forbidden.
        if (script.length > 0 && (script[0] & 255) == 0xc0) return null;
        // Find OP_RVN_ASSET as an opcode, never as a byte inside pushed hashes.
        // Core's loose historical classifier needs a separate consensus path.
        int position = 0;
        while (position < script.length) {
            int opcode = script[position++] & 255;
            if (opcode == 0xc0) throw new UnsupportedOperationException("Noncanonical RVN asset script");
            long length = opcode <= 75 ? opcode : 0;
            if (opcode >= 76 && opcode <= 78) {
                int prefix = 1 << (opcode - 76);
                if (prefix > script.length - position) break;
                for (int i = 0; i < prefix; i++) length |= (script[position++] & 255L) << (8 * i);
            }
            if (length > script.length - position) break;
            position += (int) length;
        }
        return null;
    }
}
