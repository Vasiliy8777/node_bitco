package ru.bitcoin.node.p2p.address;

/** Local resource policy, shared by addr and addrv2 on one connection. */
final class AddressRelayBudget {
    static final int ADDRESS_BURST = 1000;
    static final int MESSAGE_BURST = 10;
    private double addresses = ADDRESS_BURST;
    private double messages = MESSAGE_BURST;
    private long updated;

    AddressRelayBudget(long now) { updated = now; }

    synchronized boolean allowMessage(long now) {
        refill(now);
        if (messages < 1) return false;
        messages--;
        return true;
    }

    synchronized int takeAddresses(int requested, long now) {
        refill(now);
        int granted = Math.min(requested, (int) addresses);
        addresses -= granted;
        return granted;
    }

    private void refill(long now) {
        long elapsed = now - updated;
        if (elapsed <= 0) return;
        double seconds = elapsed / 1_000_000_000.0;
        addresses = Math.min(ADDRESS_BURST, addresses + seconds);
        messages = Math.min(MESSAGE_BURST, messages + seconds);
        updated = now;
    }
}