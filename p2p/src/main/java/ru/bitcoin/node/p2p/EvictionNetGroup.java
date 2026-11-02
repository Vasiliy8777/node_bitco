package ru.bitcoin.node.p2p;

import java.net.InetAddress;
import java.util.Arrays;

/** Default (non-ASMap) Core 32.1 netgroup bytes for socket IP addresses. */
final class EvictionNetGroup {
    private EvictionNetGroup() { }

    static boolean local(InetAddress address) {
        byte[] raw = address.getAddress();
        return raw.length == 4 ? raw[0] == 127 || raw[0] == 0 : address.isLoopbackAddress();
    }

    static byte[] group(InetAddress address) {
        byte[] raw = address.getAddress();
        if (raw.length == 4) return routableV4(raw) ? new byte[]{1, raw[0], raw[1]} : new byte[]{0};
        if (prefix(raw, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff)) {
            byte[] ipv4 = Arrays.copyOfRange(raw, 12, 16);
            return routableV4(ipv4) ? new byte[]{1, ipv4[0], ipv4[1]} : new byte[]{0};
        }
        if (prefix(raw, 0xfd, 0x6b, 0x88, 0xc0, 0x87, 0x24)) {
            byte[] group = new byte[11]; group[0] = 6;
            System.arraycopy(raw, 6, group, 1, 10); return group;
        }
        if (address.isAnyLocalAddress() || local(address) || (raw[0] & 0xfe) == 0xfc
                || prefix(raw, 0xfe, 0x80, 0, 0, 0, 0, 0, 0)
                || prefix(raw, 0x20, 1, 0x0d, 0xb8)
                || (prefix(raw, 0x3f, 0xff) && (raw[2] & 0xf0) == 0)
                || (prefix(raw, 0x20, 1, 0) && ((raw[3] & 0xf0) == 0x10 || (raw[3] & 0xf0) == 0x20)))
            return new byte[]{0};
        if (prefix(raw, 0x20, 2)) return new byte[]{1, raw[2], raw[3]}; // 6to4
        if (prefix(raw, 0x20, 1, 0, 0)) return new byte[]{1, (byte) ~raw[12], (byte) ~raw[13]}; // Teredo
        if (prefix(raw, 0, 0x64, 0xff, 0x9b, 0, 0, 0, 0, 0, 0, 0, 0)
                || prefix(raw, 0, 0, 0, 0, 0, 0, 0, 0, 0xff, 0xff, 0, 0))
            return new byte[]{1, raw[12], raw[13]};
        boolean heNet = prefix(raw, 0x20, 1, 4, 0x70);
        byte[] group = new byte[heNet ? 6 : 5]; group[0] = 2;
        System.arraycopy(raw, 0, group, 1, 4);
        if (heNet) group[5] = (byte) (raw[4] | 0x0f);
        return group;
    }

    private static boolean routableV4(byte[] raw) {
        int a = raw[0] & 255, b = raw[1] & 255, c = raw[2] & 255;
        return !(a == 0 || a == 127 || a == 10 || (a == 192 && b == 168)
                || (a == 172 && b >= 16 && b <= 31) || (a == 198 && (b == 18 || b == 19))
                || (a == 169 && b == 254) || (a == 100 && b >= 64 && b <= 127)
                || (a == 192 && b == 0 && c == 2) || (a == 198 && b == 51 && c == 100)
                || (a == 203 && b == 0 && c == 113)
                || Arrays.equals(raw, new byte[]{-1, -1, -1, -1}));
    }

    private static boolean prefix(byte[] raw, int... prefix) {
        for (int index = 0; index < prefix.length; index++) if ((raw[index] & 255) != prefix[index]) return false;
        return true;
    }
}
