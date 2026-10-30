package ru.bitcoin.node.rvn.app.mining;

import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.consensus.*;
import ru.bitcoin.node.rvn.p2p.RvnPeerSession;
import ru.bitcoin.node.rvn.pow.*;
import ru.bitcoin.node.rvn.protocol.*;

import java.io.*;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.*;
import java.security.SecureRandom;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Explicit offline experiment; never opens chainstate or submits a block.
 * Unlike a unit test this may require months to reach the frozen mainnet target. */
public final class RvnRealTargetGpuBenchmark {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RvnRealTargetGpuBenchmark.class);
    private static final HexFormat HEX = HexFormat.of();
    private RvnRealTargetGpuBenchmark() {}

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        if (args.length == 4 && args[0].equals("capture")) {
            capture(args[1], args[2], Path.of(args[3]));
        } else if (args.length == 6 && args[0].equals("run")) {
            run(Path.of(args[1]), Path.of(args[2]), Path.of(args[3]), args[4], Long.parseLong(args[5]));
        } else throw new IllegalArgumentException(
                "capture peer block-id snapshot.bin | run snapshot.bin worker.py temporary-directory python seconds (0=unlimited)");
    }

    private static void capture(String peer, String id, Path destination) throws Exception {
        byte[] requested = reverse(HEX.parseHex(id));
        if (requested.length != 32) throw new IllegalArgumentException("Block id must be 32 bytes");
        try (var session = RvnPeerSession.connect(RvnNetwork.MAINNET, peer, 8767, 5000, 256)) {
            var inventory = new ByteArrayOutputStream();
            RvnCompactSize.write(inventory, 1);
            RvnWire.writeU32(inventory, 0x40000002L);
            inventory.writeBytes(requested);
            session.send(new RvnMessage("getdata", inventory.toByteArray()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (System.nanoTime() < deadline) {
                var message = session.poll(1000);
                if (message == null) continue;
                if (message.command().equals("notfound")) throw new IOException("Snapshot block unavailable");
                if (!message.command().equals("block")) continue;
                var block = RvnBlockCodec.parse(message.payload());
                RvnBlockBodyChecks.requireValid(block, true);
                var proof = RvnKawpowProof.requireValidMix(new RvnKawpowLightEngine(65536), block.header().serialize());
                if (!Arrays.equals(proof.powDigestWire(), requested)) throw new IOException("Snapshot id mismatch");
                var target = target(block);
                if (new BigInteger(1, reverse(proof.powDigestWire())).compareTo(target) > 0)
                    throw new IOException("Snapshot proof exceeds target");
                Files.createDirectories(destination.toAbsolutePath().getParent());
                Files.write(destination, message.payload(), StandardOpenOption.CREATE_NEW);
                log("SNAPSHOT sourceId=%s peer=%s file=%s", id, peer, destination.toAbsolutePath());
                describe(block);
                return;
            }
            throw new IOException("Snapshot block response deadline exceeded");
        }
    }

    private static void run(Path snapshot, Path script, Path temporary, String python, long seconds) throws Exception {
        if (seconds < 0) throw new IllegalArgumentException("Duration must be nonnegative");
        Files.createDirectories(temporary.toAbsolutePath().getParent());
        Instant launched = Instant.now();
        long launchedNanos = System.nanoTime();
        log("LAUNCH durationSeconds=%d offline=true frozenTarget=true networkSubmission=false nonceOrder=bijective64", seconds);
        var original = RvnBlockCodec.parse(Files.readAllBytes(snapshot));
        RvnBlockBodyChecks.requireValid(original, true);
        long marker = new SecureRandom().nextLong();
        long assembly = System.nanoTime();
        var template = freshTemplate(original, marker);
        log("TEMPLATE assemblyMs=%.3f extraNonceMarker=%s originalNonceNotReused=true",
                (System.nanoTime() - assembly) / 1e6, Long.toUnsignedString(marker));
        describe(template);
        Files.write(temporary.resolveSibling("benchmark-template.bin"), RvnBlockCodec.serialize(template));
        var target = target(template);
        long height = template.header().declaredHeight();
        byte[] digest = reverse(template.header().kawpowInputDigest());
        try (var worker = new RvnCudaKawpowWorker(python, script, temporary, 0, 1L << 30)) {
            log("SELF_TEST_START");
            RvnCudaKawpowSelfTest.run(worker);
            log("SELF_TEST_OK; preparing actual epoch=%d", RvnKawpowEpoch.fromHeight(height));
            // A sampled GPU/CPU comparison in the actual epoch before timed search.
            worker.hashBatch(List.of(new RvnCudaKawpowWorker.Work(height, digest, 0)));
            long started = System.nanoTime(), report = started, batches = 0, hashes = 0, reportHashes = 0;
            log("SEARCH_START height=%d headerHash=%s firstCounter=0 batchSize=65536 warmupSeconds=%.3f",
                    height, HEX.formatHex(digest), (started - launchedNanos) / 1e9);
            long nonce = 0;
            boolean stopped = false;
            while (seconds == 0 || (System.nanoTime() - started) / 1e9 < seconds) {
                if (batches % 128 == 0 && Files.exists(temporary.resolveSibling("stop-requested"))) {
                    stopped = true;
                    break;
                }
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Benchmark interrupted");
                var batch = worker.searchPermuted(height, digest, nonce, 65536, target);
                if (batch.overflow() || batch.tested() != 65536) throw new IOException("Unexpected search response");
                batches++; hashes = Math.addExact(hashes, batch.tested());
                long now = System.nanoTime();
                if (!batch.candidates().isEmpty()) {
                    var candidate = batch.candidates().getFirst();
                    var block = RvnBlockTemplateBuilder.withProof(template, candidate.nonce(), reverse(candidate.mix()));
                    RvnBlockBodyChecks.requireValid(block, true);
                    // Worker already checked hash/mix/target independently on CPU.
                    if (new BigInteger(1, candidate.hash()).compareTo(target) > 0) throw new IOException("Target mismatch");
                    Files.write(temporary.resolveSibling("benchmark-found-block.bin"), RvnBlockCodec.serialize(block),
                            StandardOpenOption.CREATE_NEW);
                    log("FOUND searchStarted=%s foundAt=%s elapsedSeconds=%.6f totalSeconds=%.6f batches=%d hashes=%d"
                                    + " MHs=%.3f nonce=%s hash=%s mix=%s cpuVerified=true networkSubmitted=false",
                            launched.plusNanos(started - launchedNanos), Instant.now(), (now - started) / 1e9,
                            (now - launchedNanos) / 1e9, batches, hashes, hashes * 1000.0 / (now - started),
                            Long.toUnsignedString(candidate.nonce()), HEX.formatHex(candidate.hash()), HEX.formatHex(candidate.mix()));
                    return;
                }
                long next = nonce + 65536;
                if (Long.compareUnsigned(next, nonce) < 0) throw new IOException("Nonce space exhausted");
                nonce = next;
                if (now - report >= TimeUnit.SECONDS.toNanos(10)) {
                    double rate = hashes * 1e9 / (now - started);
                    double mean = expectedHashes(target) / rate;
                    log("PROGRESS jobs=1 batches=%d hashes=%d nextCounter=%s elapsedSeconds=%.3f MHs=%.3f intervalMHs=%.3f"
                                    + " meanDays=%.4f medianDays=%.4f p95Days=%.4f probabilityFoundByNow=%.9f",
                            batches, hashes, Long.toUnsignedString(nonce), (now - started) / 1e9, rate / 1e6,
                            (hashes - reportHashes) * 1000.0 / (now - report), mean / 86400,
                            mean * Math.log(2) / 86400, mean * -Math.log(.05) / 86400,
                            -Math.expm1(-hashes / expectedHashes(target)));
                    report = now; reportHashes = hashes;
                }
            }
            long end = System.nanoTime();
            log("FINISHED result=%s batches=%d hashes=%d elapsedSeconds=%.3f MHs=%.3f"
                            + " networkSubmitted=false; absence of a hit is not a KAWPOW failure",
                    stopped ? "STOP_REQUESTED" : "NO_HIT_LIMIT_REACHED",
                    batches, hashes, (end - started) / 1e9, hashes * 1000.0 / (end - started));
        }
    }

    static RvnBlock freshTemplate(RvnBlock original, long marker) throws IOException {
        var coinbase = original.transactions().getFirst();
        var input = coinbase.inputs().getFirst();
        byte[] script = input.scriptSig();
        int offset = script.length <= 92 ? script.length : script.length - 8;
        script = Arrays.copyOf(script, Math.max(script.length, offset + 8));
        ByteBuffer.wrap(script).order(ByteOrder.LITTLE_ENDIAN).putLong(offset, marker);
        var transactions = new ArrayList<>(original.transactions());
        transactions.set(0, new Transaction(coinbase.version(), List.of(new TxIn(input.previousOutput(), script,
                input.sequence(), input.witness())), coinbase.outputs(), coinbase.lockTime()));
        byte[] header = original.header().serialize();
        System.arraycopy(MerkleTree.calculateRoot(transactions.stream().map(Transaction::txId).toList()).bytes(), 0, header, 36, 32);
        Arrays.fill(header, 80, 120, (byte) 0);
        var result = new RvnBlock(RvnBlockHeader.parse(header, RvnActivation.MAINNET_KAWPOW_TIME), transactions);
        RvnBlockBodyChecks.requireValid(result, true);
        if (Arrays.equals(result.header().kawpowInputDigest(), original.header().kawpowInputDigest()))
            throw new IOException("Experiment must not reuse an already solved header");
        return result;
    }

    static double expectedHashes(BigInteger target) {
        if (target.signum() <= 0 || target.bitLength() > 256) throw new IllegalArgumentException("Target bounds");
        return Math.scalb(1.0, 256) / target.add(BigInteger.ONE).doubleValue();
    }

    private static BigInteger target(RvnBlock block) {
        return RvnDifficultyScreen.requireWithinLimit(block.header().compactBits(), RvnMainnetDifficulty.POW_LIMIT);
    }

    private static void describe(RvnBlock block) {
        long bytes = RvnBlockCodec.size(block, true), stripped = RvnBlockCodec.size(block, false);
        log("BLOCK height=%d epoch=%d timestamp=%s nBits=%08x target=%064x transactions=%d bytes=%d strippedBytes=%d"
                        + " weight=%d expectedHashes=%.6e estimatedMeanDaysAt25MHs=%.4f",
                block.header().declaredHeight(), RvnKawpowEpoch.fromHeight(block.header().declaredHeight()),
                Instant.ofEpochSecond(block.header().timestamp()), block.header().compactBits(), target(block),
                block.transactions().size(), bytes, stripped, 3 * stripped + bytes, expectedHashes(target(block)),
                expectedHashes(target(block)) / 25e6 / 86400);
    }

    private static byte[] reverse(byte[] bytes) {
        var result = bytes.clone();
        for (int i = 0; i < result.length / 2; i++) {
            byte swap = result[i]; result[i] = result[result.length - 1 - i]; result[result.length - 1 - i] = swap;
        }
        return result;
    }

    private static void log(String format, Object... args) {
        LOG.info("{} {}", Instant.now(), String.format(Locale.ROOT, format, args));
    }
}
