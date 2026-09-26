package ru.bitcoin.node.p2p;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;

/**
 * Persistent manual IP/subnet ban list, distinct from automatic discouragement.
 */
public final class PeerBanManager {
    public record BanEntry(String subnet, long banCreated, long bannedUntil) {
    }

    private final Map<String, BanEntry> bans = new LinkedHashMap<>();
    private final PeerBanStore store;
    private final Supplier<Instant> clock;

    public PeerBanManager() {
        this.store = null;
        this.clock = Instant::now;
    }

    public PeerBanManager(Path dataDirectory) {
        this(new PeerBanStore(dataDirectory), Instant::now);
    }

    PeerBanManager(PeerBanStore store, Supplier<Instant> clock) {
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
        try {
            bans.putAll(store.load());
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load ban list", e);
        }
        purgeExpired(false);
    }

    public synchronized void ban(String subnet, long banSeconds, boolean absolute) {
        Cidr cidr = Cidr.parse(subnet);
        long now = clock.get().getEpochSecond();
        long until = absolute ? banSeconds : Math.addExact(now, banSeconds <= 0 ? 86_400L : banSeconds);
        if (until <= now) throw new IllegalArgumentException("Ban expiry must be in the future");
        String key = cidr.canonical();
        if (bans.containsKey(key)) throw new IllegalArgumentException("IP/Subnet already banned");
        bans.put(key, new BanEntry(key, now, until));
        persist();
    }

    public synchronized boolean unban(String subnet) {
        String key = Cidr.parse(subnet).canonical();
        boolean removed = bans.remove(key) != null;
        if (removed) persist();
        return removed;
    }

    public synchronized void clear() {
        bans.clear();
        persist();
    }

    public synchronized boolean isBanned(InetAddress address) {
        purgeExpired(true);
        for (BanEntry entry : bans.values()) if (Cidr.parse(entry.subnet()).contains(address)) return true;
        return false;
    }

    public synchronized List<BanEntry> entries() {
        purgeExpired(true);
        return List.copyOf(bans.values());
    }

    private void purgeExpired(boolean persistChanges) {
        long now = clock.get().getEpochSecond();
        boolean changed = bans.values().removeIf(e -> e.bannedUntil() <= now);
        if (changed && persistChanges) persist();
    }

    private void persist() {
        if (store == null) return;
        try {
            store.save(bans);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to persist ban list", e);
        }
    }

    static final class Cidr {
        private final byte[] network;
        private final int prefix;

        private Cidr(byte[] network, int prefix) {
            this.network = network;
            this.prefix = prefix;
        }

        static Cidr parse(String text) {
            try {
                String[] parts = text.trim().split("/", -1);
                InetAddress address = InetAddress.getByName(parts[0]);
                byte[] bytes = address.getAddress();
                int bits = bytes.length * 8;
                int prefix = parts.length == 1 ? bits : Integer.parseInt(parts[1]);
                if (parts.length > 2 || prefix < 0 || prefix > bits) throw new IllegalArgumentException();
                byte[] network = bytes.clone();
                for (int bit = prefix; bit < bits; bit++) network[bit / 8] &= (byte) ~(1 << (7 - bit % 8));
                return new Cidr(network, prefix);
            } catch (Exception e) {
                throw new IllegalArgumentException("Invalid IP/subnet: " + text, e);
            }
        }

        boolean contains(InetAddress address) {
            byte[] other = address.getAddress();
            if (other.length != network.length) return false;
            for (int bit = 0; bit < prefix; bit++)
                if (((network[bit / 8] ^ other[bit / 8]) & (1 << (7 - bit % 8))) != 0) return false;
            return true;
        }

        String canonical() {
            try {
                return InetAddress.getByAddress(network).getHostAddress() + "/" + prefix;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
