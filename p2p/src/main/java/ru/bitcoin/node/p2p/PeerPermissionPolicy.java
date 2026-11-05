package ru.bitcoin.node.p2p;

import java.net.InetAddress;
import java.util.*;

/** Explicit inbound rules: noban,download@numeric-CIDR;download@numeric-CIDR. */
public final class PeerPermissionPolicy {
    private record Rule(PeerBanManager.Cidr subnet, Set<PeerPermission> permissions) {}
    private final List<Rule> rules;
    private PeerPermissionPolicy(List<Rule> rules) { this.rules = List.copyOf(rules); }
    public static PeerPermissionPolicy none() { return new PeerPermissionPolicy(List.of()); }
    public static PeerPermissionPolicy parse(String specification) {
        Objects.requireNonNull(specification);
        var rules = new ArrayList<Rule>();
        if (specification.isBlank()) return none();
        for (String entry : specification.split(";", -1)) {
            var parts = entry.trim().split("@", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank())
                throw new IllegalArgumentException("Expected explicit permissions@numeric-CIDR rule");
            String literal = parts[1].trim().split("/", -1)[0];
            boolean ipv4 = literal.matches("[0-9]+(?:\\.[0-9]+){3}");
            boolean ipv6 = literal.contains(":") && literal.matches("[0-9a-fA-F:.]+");
            if (!ipv4 && !ipv6) throw new IllegalArgumentException("Permission rules require numeric IP addresses, not hostnames");
            var permissions = EnumSet.noneOf(PeerPermission.class);
            for (String name : parts[0].split(",", -1)) {
                try { permissions.add(PeerPermission.valueOf(name.trim().toUpperCase(Locale.ROOT))); }
                catch (IllegalArgumentException unsupported) { throw new IllegalArgumentException("Unsupported peer permission: " + name); }
            }
            rules.add(new Rule(PeerBanManager.Cidr.parse(parts[1].trim()), new PeerAccess(permissions, false).permissions()));
        }
        return new PeerPermissionPolicy(rules);
    }
    public PeerAccess accessFor(InetAddress address) {
        var permissions = EnumSet.noneOf(PeerPermission.class);
        if (address != null) for (var rule : rules) if (rule.subnet().contains(address)) permissions.addAll(rule.permissions());
        return new PeerAccess(permissions, false);
    }
}
