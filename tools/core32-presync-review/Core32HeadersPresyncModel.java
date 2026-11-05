package ru.bitcoin.node.review;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ChainWork;
import ru.bitcoin.node.consensus.pow.CompactTarget;
import ru.bitcoin.node.crypto.hash.SipHash24;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.serialization.BlockHeaderSerializer;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** OFFLINE REVIEW ONLY. Not built into or referenced by the running node.
 * State-machine adaptation of Bitcoin Core v32.1 headerssync.cpp (MIT).
 * Caller must check whole-message PoW/continuity, as Core's net_processing does.
 */
final class Core32HeadersPresyncModel {
    enum Phase { PRESYNC, REDOWNLOAD, FINAL }
    record Parameters(int period, int buffer) {
        Parameters { if (period <= 0 || buffer <= 0) throw new IllegalArgumentException("Invalid parameters"); }
        static Parameters forNetwork(NetworkParameters p) {
            return switch (p.network()) {
                case MAINNET -> new Parameters(649, 15400);
                case TESTNET -> new Parameters(682, 14613);
                case TESTNET4 -> new Parameters(616, 16199);
                case SIGNET -> new Parameters(629, 15885);
                case REGTEST -> new Parameters(275, 7017);
            };
        }
    }
    record Result(boolean success, boolean requestMore, List<BlockHeader> ready) {}
    private final NetworkParameters network;
    private final Parameters parameters;
    private final BlockIndex start;
    private final BigInteger threshold;
    private final long key0, key1, maxCommitments;
    private final int offset;
    private BitSet commitments = new BitSet();
    private int commitmentCount, consumed;
    private ArrayDeque<byte[]> buffer = new ArrayDeque<>();
    private BlockHeader previous;
    private long height, redownloadHeight;
    private Hash256 redownloadLast, bufferFirstParent;
    private BigInteger work, redownloadWork;
    private boolean releaseAll;
    private Phase phase = Phase.PRESYNC;

    Core32HeadersPresyncModel(NetworkParameters network, BlockIndex start, BigInteger threshold,
                             long mtp, long now, Parameters parameters, Random random) {
        this.network = network; this.start = start; this.threshold = threshold; this.parameters = parameters;
        key0 = random.nextLong(); key1 = random.nextLong(); offset = random.nextInt(parameters.period());
        long seconds = Math.addExact(Math.subtractExact(now, mtp), 7200);
        if (seconds < 0) throw new IllegalStateException("Local clock is behind start MTP");
        maxCommitments = Math.min(Integer.MAX_VALUE, Math.multiplyExact(seconds, 6) / parameters.period());
        previous = start.header(); height = start.height(); work = start.chainWork();
    }
    Phase phase() { return phase; }
    int bufferedHeaders() { return buffer.size(); }
    int commitmentCount() { return commitmentCount - consumed; }
    Hash256 nextHash() { return phase == Phase.PRESYNC ? previous.hash() : redownloadLast; }

    Result process(List<BlockHeader> received, boolean full) {
        if (phase == Phase.FINAL || received.isEmpty()) return failure();
        if (phase == Phase.PRESYNC) {
            for (var header : received) {
                long next = Math.addExact(height, 1);
                if (!header.previousBlockHash().equals(previous.hash())
                        || !permittedTransition(network, next, previous.bits().value(), header.bits().value())) return failure();
                if (next % parameters.period() == offset) {
                    if ((long) commitmentCount + 1 > maxCommitments) return failure();
                    commitments.set(commitmentCount++, commitment(header.hash()));
                }
                work = work.add(ChainWork.blockWork(header.bits().value())); previous = header; height = next;
            }
            if (work.compareTo(threshold) >= 0) {
                phase = Phase.REDOWNLOAD; redownloadHeight = start.height();
                redownloadLast = start.hash(); bufferFirstParent = start.hash(); redownloadWork = start.chainWork();
            }
            boolean more = full || phase == Phase.REDOWNLOAD;
            if (!more) finish();
            return new Result(true, more, List.of());
        }
        for (var header : received) {
            long next = Math.addExact(redownloadHeight, 1);
            long oldBits = buffer.isEmpty() ? start.header().bits().value() : bits(buffer.getLast());
            if (!header.previousBlockHash().equals(redownloadLast)
                    || !permittedTransition(network, next, oldBits, header.bits().value())) return failure();
            redownloadWork = redownloadWork.add(ChainWork.blockWork(header.bits().value()));
            if (redownloadWork.compareTo(threshold) >= 0) releaseAll = true;
            if (!releaseAll && next % parameters.period() == offset) {
                if (consumed == commitmentCount || commitments.get(consumed) != commitment(header.hash())) return failure();
                commitments.clear(consumed++);
            }
            buffer.addLast(compress(header)); redownloadHeight = next; redownloadLast = header.hash();
        }
        var ready = new ArrayList<BlockHeader>();
        while (buffer.size() > parameters.buffer() || (!buffer.isEmpty() && releaseAll)) {
            var header = expand(buffer.removeFirst(), bufferFirstParent);
            ready.add(header); bufferFirstParent = header.hash();
        }
        boolean more = !(buffer.isEmpty() && releaseAll) && full;
        if (!more) finish();
        return new Result(true, more, List.copyOf(ready));
    }
    private boolean commitment(Hash256 hash) { return (SipHash24.hash(key0, key1, hash.bytes()) & 1) != 0; }
    private Result failure() { finish(); return new Result(false, false, List.of()); }
    void finish() {
        phase = Phase.FINAL; commitments = new BitSet(); buffer = new ArrayDeque<>();
        previous = null; redownloadLast = null; bufferFirstParent = null; commitmentCount = 0; consumed = 0;
    }
    static boolean permittedTransition(NetworkParameters p, long height, long oldBits, long newBits) {
        if (p.allowMinDifficultyBlocks()) return true;
        if (height % p.difficultyAdjustmentInterval() != 0) return oldBits == newBits;
        var old = CompactTarget.decode(oldBits); var observed = CompactTarget.decode(newBits);
        var timespan = BigInteger.valueOf(p.targetTimespanSeconds());
        var largest = old.multiply(timespan.multiply(BigInteger.valueOf(4))).divide(timespan).min(p.powLimit());
        var smallest = old.multiply(timespan.divide(BigInteger.valueOf(4))).divide(timespan).min(p.powLimit());
        return observed.compareTo(CompactTarget.decode(CompactTarget.encode(largest))) <= 0
                && observed.compareTo(CompactTarget.decode(CompactTarget.encode(smallest))) >= 0;
    }
    private static byte[] compress(BlockHeader header) {
        var full = BlockHeaderSerializer.serialize(header); var packed = new byte[48];
        System.arraycopy(full, 0, packed, 0, 4); System.arraycopy(full, 36, packed, 4, 44); return packed;
    }
    private static long bits(byte[] packed) { return Integer.toUnsignedLong(ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN).getInt(40)); }
    private static BlockHeader expand(byte[] packed, Hash256 parent) {
        var bytes = ByteBuffer.wrap(packed).order(ByteOrder.LITTLE_ENDIAN);
        int version = bytes.getInt(); byte[] merkle = new byte[32]; bytes.get(merkle);
        return new BlockHeader(version, parent, new Hash256(merkle), new UInt32(Integer.toUnsignedLong(bytes.getInt())),
                new UInt32(Integer.toUnsignedLong(bytes.getInt())), new UInt32(Integer.toUnsignedLong(bytes.getInt())));
    }
}
