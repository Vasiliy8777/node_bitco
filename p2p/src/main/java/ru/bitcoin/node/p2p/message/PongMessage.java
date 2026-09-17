package ru.bitcoin.node.p2p.message;

public record PongMessage(
        long nonce
) {
}