package ru.bitcoin.node.stratum.job;

import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;

/** Immutable job; coinbase Merkle siblings are shared by all miners. */
public final class MiningJob {
    private static final int EXTRA_SIZE = ExtraNonceManager.EXTRANONCE1_SIZE + ExtraNonceManager.EXTRANONCE2_SIZE;
    private final String id;
    private final MiningWork work;
    private final long createdNanos;
    private final byte[] scriptPrefix;
    private final String coinbase1;
    private final String coinbase2;
    private final List<Hash256> branch;

    public MiningJob(String id, MiningWork work) {
        this.id = Objects.requireNonNull(id);
        this.work = Objects.requireNonNull(work);
        createdNanos = System.nanoTime();
        var coinbase = work.block().transactions().getFirst();
        if (!coinbase.isCoinbase() || coinbase.inputs().size() != 1)
            throw new IllegalArgumentException("Expected coinbase");
        byte[] script = coinbase.inputs().getFirst().scriptSig();
        if (script.length < EXTRA_SIZE + 1 || script.length > 100)
            throw new IllegalArgumentException("Coinbase must reserve sixteen extranonce bytes");
        for (int i = script.length - EXTRA_SIZE; i < script.length; i++)
            if (script[i] != 0) throw new IllegalArgumentException("Nonzero extranonce placeholder");
        scriptPrefix = Arrays.copyOf(script, script.length - EXTRA_SIZE);
        byte[] serialized = TransactionSerializer.serializeLegacy(coinbase);
        // version(4), input count(1), outpoint(36), script length(1); scriptSig <= 100 bytes.
        int split = 42 + scriptPrefix.length;
        coinbase1 = HexFormat.of().formatHex(Arrays.copyOfRange(serialized, 0, split));
        coinbase2 = HexFormat.of().formatHex(Arrays.copyOfRange(serialized, split + EXTRA_SIZE, serialized.length));
        var leaves = new ArrayList<>(work.block().transactions().stream().map(Transaction::txId).toList());
        var siblings = new ArrayList<Hash256>();
        while (leaves.size() > 1) {
            siblings.add(leaves.get(1));
            if ((leaves.size() & 1) != 0) leaves.add(leaves.getLast());
            var next = new ArrayList<Hash256>();
            for (int i = 0; i < leaves.size(); i += 2) next.add(combine(leaves.get(i), leaves.get(i + 1)));
            leaves = next;
        }
        branch = List.copyOf(siblings);
    }

    public String id() { return id; }
    public MiningWork work() { return work; }
    public long createdNanos() { return createdNanos; }

    public List<Object> notification(boolean cleanJobs) {
        var header = work.block().header();
        byte[] previous = header.previousBlockHash().bytes();
        // Stratum V1 uses getwork word order: reverse each uint32 of the wire previous hash.
        for (int i = 0; i < previous.length; i += 4) {
            byte a = previous[i]; previous[i] = previous[i + 3]; previous[i + 3] = a;
            byte b = previous[i + 1]; previous[i + 1] = previous[i + 2]; previous[i + 2] = b;
        }
        return List.of(id, HexFormat.of().formatHex(previous), coinbase1, coinbase2,
                branch.stream().map(hash -> HexFormat.of().formatHex(hash.bytes())).toList(),
                String.format("%08x", header.version()), String.format("%08x", header.bits().value()),
                String.format("%08x", header.timestamp().value()), cleanJobs);
    }

    public Candidate candidate(byte[] extraNonce1, byte[] extraNonce2, long time, long nonce) {
        return candidate(extraNonce1, extraNonce2, time, nonce, work.block().header().version());
    }

    public Candidate candidate(byte[] extraNonce1, byte[] extraNonce2, long time, long nonce, int version) {
        if (extraNonce1.length != ExtraNonceManager.EXTRANONCE1_SIZE || extraNonce2.length != ExtraNonceManager.EXTRANONCE2_SIZE)
            throw new IllegalArgumentException("Invalid extranonce size");
        byte[] script = Arrays.copyOf(scriptPrefix, scriptPrefix.length + EXTRA_SIZE);
        System.arraycopy(extraNonce1, 0, script, scriptPrefix.length, extraNonce1.length);
        System.arraycopy(extraNonce2, 0, script, scriptPrefix.length + extraNonce1.length, extraNonce2.length);
        var original = work.block().transactions().getFirst();
        var input = original.inputs().getFirst();
        var coinbase = new Transaction(original.version(), List.of(new TxIn(input.previousOutput(), script,
                input.sequence(), input.witness())), original.outputs(), original.lockTime());
        Hash256 root = coinbase.txId();
        for (var sibling : branch) root = combine(root, sibling);
        var originalHeader = work.block().header();
        var header = new BlockHeader(version, originalHeader.previousBlockHash(), root,
                new UInt32(time), originalHeader.bits(), new UInt32(nonce));
        return new Candidate(header, coinbase);
    }

    public Block block(Candidate candidate) {
        var transactions = new ArrayList<>(work.block().transactions());
        transactions.set(0, candidate.coinbase());
        return new Block(candidate.header(), transactions);
    }

    public record Candidate(BlockHeader header, Transaction coinbase) { }

    private static Hash256 combine(Hash256 left, Hash256 right) {
        byte[] bytes = Arrays.copyOf(left.bytes(), 64);
        System.arraycopy(right.bytes(), 0, bytes, 32, 32);
        return Hash256Digest.hash(bytes);
    }
}
