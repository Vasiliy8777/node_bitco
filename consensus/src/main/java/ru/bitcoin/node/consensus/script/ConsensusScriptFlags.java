package ru.bitcoin.node.consensus.script;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.script.ScriptVerifyFlags;

public final class ConsensusScriptFlags {

    private ConsensusScriptFlags() {
    }

    public static int forBlock(
            long blockHeight,
            Hash256 blockHash,
            NetworkParameters networkParameters
    ) {
        if (blockHeight < 0) {
            throw new IllegalArgumentException(
                    "blockHeight must not be negative"
            );
        }

        if (blockHash == null) {
            throw new IllegalArgumentException(
                    "blockHash must not be null"
            );
        }

        if (networkParameters == null) {
            throw new IllegalArgumentException(
                    "networkParameters must not be null"
            );
        }

        int flags =
                ScriptVerifyFlags.NONE;

        /*
         * BIP16 / P2SH.
         *
         * Bitcoin Core фактически применяет P2SH
         * ко всей исторической цепочке, кроме
         * одного известного exception block
         * на mainnet/testnet3.
         */
        if (!isBip16Exception(
                blockHash,
                networkParameters
        )) {

            flags |=
                    ScriptVerifyFlags.P2SH;
        }

        /*
         * BIP66.
         */
        if (blockHeight
                >= networkParameters.bip66Height()) {

            flags |=
                    ScriptVerifyFlags.DERSIG;
        }

        /*
         * BIP65 / OP_CHECKLOCKTIMEVERIFY.
         */
        if (blockHeight
                >= networkParameters.bip65Height()) {

            flags |=
                    ScriptVerifyFlags.CHECKLOCKTIMEVERIFY;
        }
        /*
         * BIP112 / OP_CHECKSEQUENCEVERIFY.
         *
         * BIP68, BIP112 и BIP113 активируются
         * одной deployment-группой CSV.
         */
        if (blockHeight
                >= networkParameters.csvHeight()) {

            flags |=
                    ScriptVerifyFlags.CHECKSEQUENCEVERIFY;
        }
        /*
         * BIP141 / SegWit.
         */
        if (blockHeight
                >= networkParameters.segwitHeight()) {

            flags |=
                    ScriptVerifyFlags.WITNESS;
        }

        return flags;
    }

    private static boolean isBip16Exception(
            Hash256 blockHash,
            NetworkParameters networkParameters
    ) {

        Hash256 exception =
                networkParameters
                        .bip16ExceptionBlockHash();

        return exception != null
                && exception.equals(
                blockHash
        );
    }
}