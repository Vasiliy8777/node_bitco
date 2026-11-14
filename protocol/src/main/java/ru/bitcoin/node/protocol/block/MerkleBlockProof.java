package ru.bitcoin.node.protocol.block;

// Partial Merkle tree algorithm adapted from Bitcoin Core v32.1, src/merkleblock.cpp.
// Copyright (c) 2009-2010 Satoshi Nakamoto
// Copyright (c) 2009-present The Bitcoin Core developers
// Distributed under the MIT license; see /licenses/bitcoin-core-MIT.txt.

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.common.bytes.LittleEndian;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.protocol.serialization.*;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** BIP37 partial Merkle tree, also used by the transaction-inclusion RPCs. */
public final class MerkleBlockProof {
    private static final Hash256 ZERO = new Hash256(new byte[32]);
    private MerkleBlockProof() { }
    public record Extracted(BlockHeader header, long transactionCount, Hash256 root, List<Hash256> matches) {
        public Extracted { matches = List.copyOf(matches); }
        public boolean valid() { return root.equals(header.merkleRoot()); }
    }
    public static byte[] create(Block block, Set<Hash256> wanted) {
        var hashes = block.transactions().stream().map(tx -> tx.txId()).toList();
        if (hashes.isEmpty()) throw new IllegalArgumentException("Empty block");
        var tree = new Builder(hashes, wanted);
        int height = height(hashes.size());
        tree.visit(height, 0);
        var output = new ByteArrayOutputStream();
        output.writeBytes(BlockHeaderSerializer.serialize(block.header()));
        output.writeBytes(LittleEndian.uint32(hashes.size()));
        output.writeBytes(CompactSize.encode(tree.proofHashes.size()));
        for (var hash : tree.proofHashes) output.writeBytes(hash.bytes());
        byte[] flags = new byte[(tree.bits.size() + 7) / 8];
        for (int i = 0; i < tree.bits.size(); i++) if (tree.bits.get(i)) flags[i / 8] |= (byte) (1 << (i % 8));
        output.writeBytes(CompactSize.encode(flags.length)); output.writeBytes(flags);
        return output.toByteArray();
    }
    public static Extracted extract(byte[] proof) {
        var reader = new BitcoinReader(proof);
        BlockHeader header = BlockHeaderParser.parse(reader);
        long count = reader.readUInt32LE();
        long hashCount = reader.readCompactSize();
        if (hashCount > 0x02000000L) throw new IllegalArgumentException("ReadCompactSize(): size too large");
        if (hashCount > reader.remaining() / 32L) throw new IllegalArgumentException("SpanReader::read(): end of data");
        var hashes = new ArrayList<Hash256>((int) hashCount);
        for (int i = 0; i < hashCount; i++) hashes.add(new Hash256(reader.readBytes(32)));
        long flagCount = reader.readCompactSize();
        if (flagCount > 0x02000000L) throw new IllegalArgumentException("ReadCompactSize(): size too large");
        if (flagCount > reader.remaining()) throw new IllegalArgumentException("SpanReader::read(): end of data");
        byte[] flags = reader.readBytes((int) flagCount);
        // Core's SpanReader intentionally does not require end-of-input here.
        if (count == 0 || count > 4_000_000 / 240 || hashes.size() > count || flags.length * 8L < hashes.size())
            return invalid(header, count);
        var tree = new Extractor(count, hashes, flags);
        Hash256 root = tree.visit(height(count), 0);
        if (tree.bad || (tree.bitsUsed + 7) / 8 != flags.length || tree.hashesUsed != hashes.size()) return invalid(header, count);
        return new Extracted(header, count, root, tree.matches);
    }
    private static Extracted invalid(BlockHeader header, long count) { return new Extracted(header, count, ZERO, List.of()); }
    private static int height(long count) { int height = 0; while (width(count, height) > 1) height++; return height; }
    private static long width(long count, int height) { return (count + (1L << height) - 1) >> height; }
    private static Hash256 parent(Hash256 left, Hash256 right) {
        byte[] bytes = new byte[64]; System.arraycopy(left.bytes(), 0, bytes, 0, 32); System.arraycopy(right.bytes(), 0, bytes, 32, 32);
        return Hash256Digest.hash(bytes);
    }
    private static final class Builder {
        final List<List<Hash256>> levels = new ArrayList<>();
        final int[] matched;
        final List<Boolean> bits = new ArrayList<>();
        final List<Hash256> proofHashes = new ArrayList<>();
        Builder(List<Hash256> leaves, Set<Hash256> wanted) {
            levels.add(leaves); matched = new int[leaves.size() + 1];
            for (int i = 0; i < leaves.size(); i++) matched[i + 1] = matched[i] + (wanted.contains(leaves.get(i)) ? 1 : 0);
            while (levels.getLast().size() > 1) {
                var previous = levels.getLast(); var next = new ArrayList<Hash256>();
                for (int i = 0; i < previous.size(); i += 2) next.add(parent(previous.get(i), previous.get(Math.min(i + 1, previous.size() - 1))));
                levels.add(next);
            }
        }
        void visit(int height, int position) {
            int first = position << height, end = Math.min((position + 1) << height, matched.length - 1);
            boolean matches = matched[end] != matched[first]; bits.add(matches);
            if (height == 0 || !matches) proofHashes.add(levels.get(height).get(position));
            else { visit(height - 1, position * 2); if (position * 2 + 1 < levels.get(height - 1).size()) visit(height - 1, position * 2 + 1); }
        }
    }
    private static final class Extractor {
        final long count;
        final List<Hash256> hashes;
        final byte[] flags;
        final List<Hash256> matches = new ArrayList<>();
        int bitsUsed, hashesUsed;
        boolean bad;
        Extractor(long count, List<Hash256> hashes, byte[] flags) { this.count = count; this.hashes = hashes; this.flags = flags; }
        Hash256 visit(int height, int position) {
            if (bitsUsed >= flags.length * 8) { bad = true; return ZERO; }
            boolean match = (flags[bitsUsed / 8] & (1 << (bitsUsed++ % 8))) != 0;
            if (height == 0 || !match) {
                if (hashesUsed >= hashes.size()) { bad = true; return ZERO; }
                Hash256 hash = hashes.get(hashesUsed++);
                if (height == 0 && match) matches.add(hash);
                return hash;
            }
            Hash256 left = visit(height - 1, position * 2), right = left;
            if (position * 2 + 1 < width(count, height - 1)) {
                right = visit(height - 1, position * 2 + 1);
                if (left.equals(right)) bad = true;
            }
            return parent(left, right);
        }
    }
}
