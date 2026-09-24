package ru.bitcoin.node.p2p.message;

/**
 * BIP152 SENDCMPCT. Unknown versions are wire-valid and are ignored by negotiation policy.
 */
public record SendCmpctMessage(boolean highBandwidth, long version) {
}
