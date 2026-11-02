package ru.bitcoin.node.p2p;

/** Core 32.1 automatic connection budget. Manual connections are accounted separately. */
public record ConnectionLimits(int maximum, int fullRelayOutbound, int blockRelayOutbound, int inboundRelayPercent) {
    public ConnectionLimits {
        if (maximum < 0 || fullRelayOutbound < 0 || blockRelayOutbound < 0)
            throw new IllegalArgumentException("Connection counts must be nonnegative");
        if (inboundRelayPercent < 0 || inboundRelayPercent > 100)
            throw new IllegalArgumentException("inboundrelaypercent must be 0..100");
        if (maximum > 2000) {
            System.getLogger(ConnectionLimits.class.getName()).log(System.Logger.Level.WARNING,
                    "Clamping maxconnections from {0} to 2000", maximum);
            maximum = 2000;
        }
        fullRelayOutbound = Math.min(fullRelayOutbound, maximum);
        blockRelayOutbound = Math.min(blockRelayOutbound, maximum - fullRelayOutbound);
    }
    public int inbound() { return maximum - fullRelayOutbound - blockRelayOutbound; }
    public int inboundFullRelay() { return inbound() * inboundRelayPercent / 100; }
}
