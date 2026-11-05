package ru.bitcoin.node.review;

import ru.bitcoin.node.chain.BlockIndex;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.pow.*;
import ru.bitcoin.node.crypto.hash.SipHash24;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.*;
import java.math.BigInteger;
import java.util.*;

/** Standalone offline checks. No sockets, persistence, RPC, or production class writes. */
public final class HeadersPresyncModelCheck {
    private static final NetworkParameters NETWORK = NetworkParametersRegistry.regtest();
    private static final Hash256 ZERO = new Hash256(new byte[32]);
    private static final BlockHeader GENESIS = GenesisBlockFactory.create(NETWORK).header();
    private static final BlockIndex START = new BlockIndex(GENESIS.hash(), GENESIS, 0, ZERO,
            ChainWork.blockWork(GENESIS.bits().value()));
    private static int assertions;

    public static void main(String[] args) {
        thresholdAndRelease(); mutationRejected(); truncatedResponses(); memoryBounds();
        commitmentAndClockLimits(); transitionBounds(); networkParameters();
        System.out.println("{\"scenarios\":7,\"assertions\":" + assertions + ",\"failures\":0,\"productionIntegration\":false}");
    }
    private static Core32HeadersPresyncModel model(int count, int period, int buffer) {
        return new Core32HeadersPresyncModel(NETWORK, START,
                START.chainWork().add(BigInteger.valueOf(count * 2L)), GENESIS.timestamp().value(),
                1_800_000_000L, new Core32HeadersPresyncModel.Parameters(period, buffer), new Random(42));
    }
    private static void thresholdAndRelease() {
        var chain = chain(12); var model = model(12, 1, 3);
        var presync = model.process(chain, true);
        check(presync.success() && presync.requestMore() && presync.ready().isEmpty(), "Presync persisted headers");
        check(model.phase() == Core32HeadersPresyncModel.Phase.REDOWNLOAD, "Threshold transition");
        check(model.nextHash().equals(START.hash()), "Redownload must restart at chain start");
        var first = model.process(chain.subList(0, 4), true);
        check(first.ready().size() == 1 && model.bufferedHeaders() == 3, "Strict lookahead boundary");
        var last = model.process(chain.subList(4, 12), true);
        check(!last.requestMore() && last.ready().size() == 11, "Target must flush the remaining buffer");
        var all = new ArrayList<>(first.ready()); all.addAll(last.ready());
        check(hashes(all).equals(hashes(chain)), "Compressed headers changed during reconstruction");
        check(model.phase() == Core32HeadersPresyncModel.Phase.FINAL && model.commitmentCount() == 0, "Finalization did not release state");
        check(!model.process(chain, true).success(), "Finalized salt/state was reused");
    }
    private static void mutationRejected() {
        var chain = chain(12); var model = model(12, 1, 3); model.process(chain, true);
        var random = new Random(42); long k0 = random.nextLong(), k1 = random.nextLong();
        long original = SipHash24.hash(k0, k1, chain.getFirst().hash().bytes()) & 1;
        BlockHeader alternative = null;
        for (int tag = 1; tag < 1000; tag++) {
            var header = mine(GENESIS.hash(), GENESIS.timestamp().value() + 1, tag);
            if ((SipHash24.hash(k0, k1, header.hash().bytes()) & 1) != original) { alternative = header; break; }
        }
        check(alternative != null, "Could not construct a different commitment");
        var result = model.process(List.of(alternative), true);
        check(!result.success() && result.ready().isEmpty(), "Changed redownload commitment was accepted");
        check(model.phase() == Core32HeadersPresyncModel.Phase.FINAL && model.bufferedHeaders() == 0, "Mutation did not clear state");
    }
    private static void truncatedResponses() {
        var chain = chain(12); var model = model(12, 1, 3);
        var shortPresync = model.process(chain.subList(0, 2), false);
        check(shortPresync.success() && !shortPresync.requestMore() && shortPresync.ready().isEmpty(), "Short low-work reply");
        check(model.phase() == Core32HeadersPresyncModel.Phase.FINAL, "Short presync did not end");
        model = model(12, 1, 3); model.process(chain, true);
        var shortRedownload = model.process(chain.subList(0, 2), false);
        check(shortRedownload.success() && !shortRedownload.requestMore() && shortRedownload.ready().isEmpty(), "Short redownload leaked lookahead");
        check(model.bufferedHeaders() == 0, "Aborted redownload retains headers");
        model = model(12, 1, 3);
        var badParent = mine(ZERO, GENESIS.timestamp().value() + 1, 7);
        check(!model.process(List.of(badParent), true).success(), "Disconnected presync accepted");
    }
    private static void memoryBounds() {
        var chain = chain(20_000);
        var parameters = Core32HeadersPresyncModel.Parameters.forNetwork(NETWORK);
        var model = model(chain.size(), parameters.period(), parameters.buffer());
        for (int offset = 0; offset < chain.size(); offset += 2000) {
            var result = model.process(chain.subList(offset, offset + 2000), true);
            check(result.success() && result.ready().isEmpty(), "Presync release before redownload");
            check(model.bufferedHeaders() == 0, "Presync retains full headers");
        }
        check(model.commitmentCount() <= 1 + chain.size() / parameters.period(), "Unbounded commitment density");
        var released = new ArrayList<BlockHeader>();
        for (int offset = 0; offset < chain.size(); offset += 2000) {
            var result = model.process(chain.subList(offset, offset + 2000), true);
            check(result.success(), "Valid redownload rejected"); released.addAll(result.ready());
            check(model.bufferedHeaders() <= parameters.buffer(), "Lookahead exceeds configured bound");
        }
        check(hashes(released).equals(hashes(chain)), "Streaming redownload lost or reordered headers");
    }
    private static void commitmentAndClockLimits() {
        var parameters = new Core32HeadersPresyncModel.Parameters(1, 3);
        long mtp = GENESIS.timestamp().value();
        var model = new Core32HeadersPresyncModel(NETWORK, START, BigInteger.valueOf(100), mtp,
                mtp - 7200, parameters, new Random(42));
        check(!model.process(chain(1), true).success(), "Exceeded time-derived commitment limit");
        boolean rejected = false;
        try { new Core32HeadersPresyncModel(NETWORK, START, BigInteger.TEN, mtp, mtp - 7201, parameters, new Random(42)); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "Clock behind MTP bound accepted");
    }
    private static void transitionBounds() {
        var mainnet = NetworkParametersRegistry.mainnet(); long old = 0x1c0ffff0L;
        var target = CompactTarget.decode(old);
        check(Core32HeadersPresyncModel.permittedTransition(mainnet, 1, old, old), "Stable difficulty");
        check(!Core32HeadersPresyncModel.permittedTransition(mainnet, 1, old, old - 1), "Off-boundary change");
        check(Core32HeadersPresyncModel.permittedTransition(mainnet, 2016, old, CompactTarget.encode(target.divide(BigInteger.valueOf(4)))), "Exact increase bound");
        check(Core32HeadersPresyncModel.permittedTransition(mainnet, 2016, old, CompactTarget.encode(target.multiply(BigInteger.valueOf(4)))), "Exact decrease bound");
        check(!Core32HeadersPresyncModel.permittedTransition(mainnet, 2016, old, CompactTarget.encode(target.divide(BigInteger.valueOf(8)))), "Excessive increase");
        check(!Core32HeadersPresyncModel.permittedTransition(mainnet, 2016, old, CompactTarget.encode(target.multiply(BigInteger.valueOf(8)))), "Excessive decrease");
        check(Core32HeadersPresyncModel.permittedTransition(NETWORK, 1, old, old - 1), "Minimum-difficulty network exception");
    }
    private static void networkParameters() {
        check(Core32HeadersPresyncModel.Parameters.forNetwork(NetworkParametersRegistry.mainnet()).equals(new Core32HeadersPresyncModel.Parameters(649, 15400)), "Core mainnet parameters");
        check(Core32HeadersPresyncModel.Parameters.forNetwork(NETWORK).equals(new Core32HeadersPresyncModel.Parameters(275, 7017)), "Core regtest parameters");
    }
    private static List<BlockHeader> chain(int count) {
        var result = new ArrayList<BlockHeader>(); var previous = GENESIS;
        for (int i = 0; i < count; i++) { previous = mine(previous.hash(), previous.timestamp().value() + 1, 0); result.add(previous); }
        return result;
    }
    private static BlockHeader mine(Hash256 parent, long timestamp, int tag) {
        byte[] merkle = new byte[32]; merkle[0] = (byte) tag; merkle[1] = (byte) (tag >>> 8);
        for (long nonce = 0; nonce < 10000; nonce++) {
            var header = new BlockHeader(4, parent, new Hash256(merkle), new UInt32(timestamp), GENESIS.bits(), new UInt32(nonce));
            if (ProofOfWork.isValid(header, NETWORK)) return header;
        }
        throw new AssertionError("Regtest mining failed");
    }
    private static List<Hash256> hashes(List<BlockHeader> headers) { return headers.stream().map(BlockHeader::hash).toList(); }
    private static void check(boolean condition, String message) { assertions++; if (!condition) throw new AssertionError(message); }
}
