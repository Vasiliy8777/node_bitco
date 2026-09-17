package ru.bitcoin.node.p2p.message;

public record PingMessage(
        long nonce
) {
}