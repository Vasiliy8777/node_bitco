package ru.bitcoin.node.rvn.chain.assets;

import ru.bitcoin.node.rvn.chain.RvnBlockBodyChecks;
import java.util.*;
import java.util.function.Predicate;
import static ru.bitcoin.node.rvn.chain.assets.RvnAssetScriptCodec.*;

/** Canonical asset metadata transitions only. Does NOT authorize transactions:
 * burn outputs, owner inputs/change, asset conservation, null restriction data,
 * verifier expressions and atomic registry persistence remain separate checks. */
public final class RvnAssetMetadataRules {
    private RvnAssetMetadataRules() {}
    public record Activation(boolean assets, boolean messages, boolean restricted, boolean transferOverflow) {
        /** Standalone callers use current rules; historical admission supplies branch state. */
        public Activation(boolean assets, boolean messages, boolean restricted) {
            this(assets, messages, restricted, true);
        }
    }
    public record Metadata(String name, long supply, int units, boolean reissuable, HashData hash) {
        public Metadata {
            if (RvnAssetNames.requireValid(name) == RvnAssetNames.Type.OWNER)
                throw new IllegalArgumentException("Owner token has no issuance metadata");
            if (supply <= 0 || supply > RvnBlockBodyChecks.MAX_MONEY || !amountMatchesUnits(supply, units))
                throw new IllegalArgumentException("Invalid RVN asset supply/units");
        }
    }

    public static Metadata issue(Issue issue, Activation activation, Predicate<String> exists) {
        Objects.requireNonNull(exists);
        var type = activeType(issue.name(), activation);
        if (type == RvnAssetNames.Type.OWNER || exists.test(issue.name()))
            throw new SecurityException("RVN asset name already exists or is an owner token");
        if (issue.amount() <= 0 || issue.amount() > RvnBlockBodyChecks.MAX_MONEY
                || !amountMatchesUnits(issue.amount(), issue.units())) fail("issuance amount/units");
        if (type == RvnAssetNames.Type.UNIQUE || type == RvnAssetNames.Type.CHANNEL) {
            if (issue.units() != 0 || issue.amount() != RvnBlockBodyChecks.COIN || issue.reissuable())
                fail("unique/channel issuance parameters");
        }
        if (type == RvnAssetNames.Type.QUALIFIER || type == RvnAssetNames.Type.SUB_QUALIFIER) {
            if (issue.units() != 0 || issue.amount() < RvnBlockBodyChecks.COIN
                    || issue.amount() > 10 * RvnBlockBodyChecks.COIN || issue.reissuable()) fail("qualifier issuance parameters");
        }
        requireHashActivation(issue.hash(), activation);
        return new Metadata(issue.name(), issue.amount(), issue.units(), issue.reissuable(), issue.hash());
    }

    public static Metadata reissue(Reissue reissue, Metadata previous, Activation activation) {
        Objects.requireNonNull(previous);
        activeType(reissue.name(), activation);
        if (!reissue.name().equals(previous.name()) || !previous.reissuable()) fail("reissue name/permission");
        if (reissue.amount() < 0 || reissue.amount() >= RvnBlockBodyChecks.MAX_MONEY
                || previous.supply() > RvnBlockBodyChecks.MAX_MONEY - reissue.amount()) fail("reissue supply");
        if (!amountMatchesUnits(reissue.amount(), previous.units())) fail("reissue amount granularity");
        if (reissue.units() < -1 || reissue.units() > 8
                || (reissue.units() != -1 && reissue.units() < previous.units())) fail("reissue units decrease");
        requireHashActivation(reissue.hash(), activation);
        return new Metadata(previous.name(), previous.supply() + reissue.amount(),
                reissue.units() == -1 ? previous.units() : reissue.units(), reissue.reissuable(),
                reissue.hash() == null ? previous.hash() : reissue.hash());
    }

    public static boolean amountMatchesUnits(long amount, int units) {
        if (amount < 0 || units < 0 || units > 8) return false;
        long scale = 1;
        for (int i = units; i < 8; i++) scale *= 10;
        return amount % scale == 0;
    }

    private static RvnAssetNames.Type activeType(String name, Activation activation) {
        Objects.requireNonNull(activation);
        var type = RvnAssetNames.requireValid(name);
        if (!activation.assets()) fail("assets are not active");
        if (type == RvnAssetNames.Type.CHANNEL && !activation.messages()) fail("messages are not active");
        if ((type == RvnAssetNames.Type.QUALIFIER || type == RvnAssetNames.Type.SUB_QUALIFIER
                || type == RvnAssetNames.Type.RESTRICTED) && !activation.restricted()) fail("restricted assets are not active");
        return type;
    }

    private static void requireHashActivation(HashData hash, Activation activation) {
        if (hash != null && hash.marker() == 0x54 && !activation.messages()) fail("transaction metadata hash before messaging activation");
    }
    private static void fail(String detail) { throw new SecurityException("RVN asset " + detail); }
}
