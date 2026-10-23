package ru.bitcoin.node.rvn.app.config;

import ru.bitcoin.node.rvn.chain.RvnPayoutAddress;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import java.util.Optional;

/** Resolved mining payout without retaining the original environment value. */
public final class RvnMiningSettings {
    private final RvnPayoutAddress payout;
    public RvnMiningSettings(RvnNetwork network, String address) {
        payout = address == null || address.isBlank() ? null : RvnPayoutAddress.parse(address, network);
    }
    public Optional<RvnPayoutAddress> payout() { return Optional.ofNullable(payout); }
    public RvnPayoutAddress requirePayout() {
        if (payout == null) throw new IllegalStateException("RVN_MINING_ADDRESS is required for RVN mining");
        return payout;
    }
    @Override public String toString() { return "RvnMiningSettings[configured=" + (payout != null) + "]"; }
}
