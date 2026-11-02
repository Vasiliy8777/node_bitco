package ru.bitcoin.node.p2p;

import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.p2p.address.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Core-style clean-shutdown anchor lifecycle, with this application's own checksummed file format. */
public final class BlockRelayAnchorStore {
    public static final int MAX_ANCHORS = 2;
    private final Path path;
    private final int magic;

    public BlockRelayAnchorStore(Path networkDirectory, long networkMagic) {
        path = Objects.requireNonNull(networkDirectory).resolve("block-relay-anchors.dat");
        magic = (int) networkMagic;
    }

    /** Consume once, including on corrupt data: a crash must not repeatedly reuse old anchors. */
    public List<PeerAddress> consume() throws IOException {
        if (!Files.exists(path)) return List.of();
        try {
            long size = Files.size(path);
            if (size < 38 || size > 1024) throw new IOException("Invalid block relay anchor file size");
            byte[] data;
            try (var input = Files.newInputStream(path)) { data = input.readNBytes(1025); }
            if (data.length < 38 || data.length > 1024) throw new IOException("Invalid block relay anchor file size");
            byte[] payload = Arrays.copyOf(data, data.length - 32);
            if (!java.security.MessageDigest.isEqual(Hash256Digest.hashBytes(payload), Arrays.copyOfRange(data, data.length - 32, data.length)))
                throw new IOException("Block relay anchor checksum mismatch");
            var input = new DataInputStream(new ByteArrayInputStream(payload));
            if (input.readInt() != magic || input.readUnsignedByte() != 1) throw new IOException("Wrong anchor network or format");
            int count = input.readUnsignedByte();
            if (count > MAX_ANCHORS) throw new IOException("Too many block relay anchors");
            var result = new ArrayList<PeerAddress>();
            for (int i = 0; i < count; i++) {
                int networkId = input.readUnsignedByte();
                var network = Arrays.stream(PeerAddressNetwork.values()).filter(n -> n.bip155Id() == networkId)
                        .findFirst().orElseThrow(() -> new IOException("Unknown anchor address network"));
                int length = input.readUnsignedByte();
                if (length != network.addressLength()) throw new IOException("Invalid anchor address length");
                byte[] raw = new byte[length]; input.readFully(raw);
                try { result.add(new PeerAddress(network, raw, input.readUnsignedShort(), input.readLong())); }
                catch (IllegalArgumentException invalid) { throw new IOException("Invalid anchor endpoint", invalid); }
            }
            if (input.available() != 0) throw new IOException("Trailing anchor data");
            return List.copyOf(result);
        } finally { Files.deleteIfExists(path); }
    }

    public void save(List<PeerAddress> addresses) throws IOException {
        var selected = addresses.stream().distinct().limit(MAX_ANCHORS).toList();
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        output.writeInt(magic); output.writeByte(1); output.writeByte(selected.size());
        for (var address : selected) {
            byte[] raw = address.rawAddress();
            output.writeByte(address.network().bip155Id()); output.writeByte(raw.length); output.write(raw);
            output.writeShort(address.port()); output.writeLong(address.services());
        }
        byte[] payload = bytes.toByteArray();
        bytes.writeBytes(Hash256Digest.hashBytes(payload));
        Files.createDirectories(path.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), "block-relay-anchors-", ".tmp");
        try {
            try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(bytes.toByteArray());
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try { Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unsupported) { Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
