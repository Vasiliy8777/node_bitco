package ru.bitcoin.node.rvn.chain.assets;

import java.util.*;
import java.util.function.Function;

import static ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks.*;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;

/** Accounting for canonical asset transfers except vote assets, not block admission.
 * Callers must resolve each spent outpoint exactly once, verify signatures and classify
 * every script. Minting and historical envelopes are separate; restricted transfers
 * additionally require parent-snapshot freeze/verifier checks in the block validator.
 */
public final class RvnAssetTransferChecks {
    private RvnAssetTransferChecks() {}

    public static Map<String, Long> verify(List<? extends Payload> spent,
                                          List<? extends Payload> outputs,
                                          Function<String, RvnAssetMetadataRules.Metadata> metadata) {
        // Compatibility API assumes assets/messages active. Admission must supply
        // the explicit branch activation overload below.
        return verify(spent, outputs, metadata, new RvnAssetMetadataRules.Activation(true, true, false));
    }

    public static Map<String, Long> verify(List<? extends Payload> spent,
                                          List<? extends Payload> outputs,
                                          Function<String, RvnAssetMetadataRules.Metadata> metadata,
                                          RvnAssetMetadataRules.Activation activation) {
        Objects.requireNonNull(metadata);
        Objects.requireNonNull(activation);
        if (!activation.assets() && (!spent.isEmpty() || !outputs.isEmpty()))
            throw new SecurityException("RVN assets not active");
        var inputs = new TreeMap<String, Long>();
        var transfers = new TreeMap<String, Long>();
        for (var payload : spent) add(inputs, payload, metadata, false, activation);
        for (var payload : outputs) add(transfers, payload, metadata, true, activation);
        if (!inputs.equals(transfers))
            throw new SecurityException("RVN asset transfer creates or destroys tokens");
        return Map.copyOf(transfers);
    }

    private static void add(Map<String, Long> totals, Payload payload,
                            Function<String, RvnAssetMetadataRules.Metadata> metadata, boolean output,
                            RvnAssetMetadataRules.Activation activation) {
        Objects.requireNonNull(payload);
        if (output && !(payload instanceof Transfer))
            throw new UnsupportedOperationException("Issuance/reissue requires transaction authorization");
        String name = payload.name();
        var type = RvnAssetNames.requireValid(name);
        boolean owner = type == RvnAssetNames.Type.OWNER;
        String base = owner ? name.substring(0, name.length() - 1) : name;
        var baseType = RvnAssetNames.requireValid(base);
        if (baseType != RvnAssetNames.Type.ROOT && baseType != RvnAssetNames.Type.SUB
                && baseType != RvnAssetNames.Type.UNIQUE && baseType != RvnAssetNames.Type.CHANNEL
                && baseType != RvnAssetNames.Type.QUALIFIER && baseType != RvnAssetNames.Type.SUB_QUALIFIER
                && baseType != RvnAssetNames.Type.RESTRICTED)
            throw new UnsupportedOperationException("Unsupported RVN asset transfer family");
        if (baseType == RvnAssetNames.Type.CHANNEL && !activation.messages())
            throw new SecurityException("RVN channel transfer before messaging activation");
        boolean qualifier = baseType == RvnAssetNames.Type.QUALIFIER || baseType == RvnAssetNames.Type.SUB_QUALIFIER;
        if (qualifier && !activation.restricted()) throw new SecurityException("RVN qualifier transfer before activation");
        if (baseType == RvnAssetNames.Type.RESTRICTED && !activation.restricted())
            throw new SecurityException("RVN restricted transfer before activation");
        long amount = payload instanceof Transfer t ? t.amount()
                : payload instanceof Issue i ? i.amount()
                : payload instanceof Reissue r ? r.amount() : COIN;
        // ContextualCheckTransferAsset always requires positive output quantities.
        // Core 4.8 bit 11 additionally bounds inputs and both running totals.
        if ((activation.transferOverflow() && (amount < 0 || amount > MAX_MONEY))
                || (output && amount <= 0) || (owner && amount != COIN))
            throw new SecurityException("RVN asset transfer amount");
        if (output && baseType == RvnAssetNames.Type.UNIQUE && amount != COIN)
            throw new SecurityException("RVN unique/channel transfer must be one token");
        if (output && qualifier && (amount < COIN || amount > 10 * COIN))
            throw new SecurityException("RVN qualifier transfer amount range");
        if ((payload instanceof Owner) != owner && !(payload instanceof Transfer))
            throw new SecurityException("RVN asset payload/name mismatch");
        // Core checks units/metadata on non-owner transfers being created, not spent coins.
        if (output && !owner) {
            var state = metadata.apply(base);
            if (state == null || !base.equals(state.name()))
                throw new SecurityException("RVN asset metadata missing or mismatched");
            if (!RvnAssetMetadataRules.amountMatchesUnits(amount, state.units()))
                throw new SecurityException("RVN asset transfer granularity");
        }
        long previous = totals.getOrDefault(name, 0L);
        if (activation.transferOverflow() && previous > MAX_MONEY - amount)
            throw new SecurityException("RVN asset transfer total exceeds money range");
        // Java long addition gives the legacy signed 64-bit wrap deterministically.
        if (amount != 0) totals.put(name, previous + amount);
    }
}
