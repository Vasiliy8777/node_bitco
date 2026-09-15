package ru.bitcoin.node.consensus.script;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.network.BitcoinNetwork;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.script.ScriptVerifyFlags;
import java.util.Objects;

/** Retrospective block script flags matching Bitcoin Core v30 GetBlockScriptFlags. */
public final class ConsensusScriptFlags {
    private static final Hash256 MAINNET_TAPROOT_EXCEPTION = Hash256.fromDisplayHex(
            "0000000000000000000f14c35b2d841e986ab5441de8c585d5ffe55ea1e395ad");
    private ConsensusScriptFlags() { }

    /** Compatibility overload: deployment state does not control retrospective script flags. */
    public static int forBlock(long height, Hash256 hash, NetworkParameters parameters, boolean taprootActive) {
        return forBlock(height, hash, parameters);
    }

    public static int forBlock(long height, Hash256 hash, NetworkParameters parameters) {
        if (height < 0) throw new IllegalArgumentException("blockHeight must not be negative");
        Objects.requireNonNull(hash, "blockHash");
        Objects.requireNonNull(parameters, "networkParameters");
        int flags = ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS | ScriptVerifyFlags.TAPROOT;
        if (hash.equals(parameters.bip16ExceptionBlockHash())) {
            flags = ScriptVerifyFlags.NONE;
        } else if (parameters.network() == BitcoinNetwork.MAINNET && MAINNET_TAPROOT_EXCEPTION.equals(hash)) {
            flags = ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS;
        }
        if (height >= parameters.bip66Height()) flags |= ScriptVerifyFlags.DERSIG;
        if (height >= parameters.bip65Height()) flags |= ScriptVerifyFlags.CHECKLOCKTIMEVERIFY;
        if (height >= parameters.csvHeight()) flags |= ScriptVerifyFlags.CHECKSEQUENCEVERIFY;
        if (height >= parameters.segwitHeight()) flags |= ScriptVerifyFlags.NULLDUMMY;
        return flags;
    }
}
