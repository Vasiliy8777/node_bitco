package ru.bitcoin.node.p2p;

/** Application state needed by outbound recovery; methods must not perform socket I/O. */
public interface OutboundPeerRecoveryView {
    long lastTipUpdateSeconds();
    boolean enabled();
    boolean blocksInFlight();
    boolean blocksInFlight(Peer peer);
    boolean hasTipWork(Peer peer);
    default boolean protectedFromChainSync(Peer peer) { return hasTipWork(peer); }
    default boolean directFetch() { return false; }
    record ChainWork(java.math.BigInteger tipWork, java.math.BigInteger peerWork,
                     java.util.List<ru.bitcoin.node.common.types.Hash256> locator) { }
    default ChainWork chainWork(Peer peer) { return null; }
    /** Whether the download coordinator has actually admitted initial headers sync. */
    default boolean headersSyncStarted(Peer peer) { return true; }
    /** New connections may delegate initial admission to their download coordinator. */
    default void requestInitialHeaders(Peer peer, java.util.List<ru.bitcoin.node.common.types.Hash256> locator)
            throws java.io.IOException { requestHeaders(peer, locator); }
    default void requestHeaders(Peer peer, java.util.List<ru.bitcoin.node.common.types.Hash256> locator)
            throws java.io.IOException { }
}
