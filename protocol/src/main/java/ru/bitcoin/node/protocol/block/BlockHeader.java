package ru.bitcoin.node.protocol.block;

public record BlockHeader(
        int version,
        byte[] previousBlockHash,
        byte[] merkleRoot,
        long timestamp,
        long bits,
        long nonce
) {}
