package ru.bitcoin.node.script;

public enum ScriptPubKeyType {

    NONSTANDARD,

    PUBKEY,

    PUBKEYHASH,

    SCRIPTHASH,

    MULTISIG,

    NULL_DATA,

    WITNESS_V0_KEYHASH,

    WITNESS_V0_SCRIPTHASH,

    WITNESS_V1_TAPROOT,

    WITNESS_UNKNOWN,

    ANCHOR
}