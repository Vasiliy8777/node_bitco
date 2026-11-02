package ru.bitcoin.node.app;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.block.*;
import ru.bitcoin.node.consensus.transaction.TransactionValidationException;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.mining.NonceMiner;
import ru.bitcoin.node.mining.coinbase.CoinbaseBuilder;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.protocol.serialization.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Consensus comparisons use blocks, including policy-nonstandard transactions; no mempool admission. */
@EnabledIfSystemProperty(named = "bitcoin.core.binary", matches = ".+")
class BitcoinCoreBlockConsensusTest {
    private static final long NOW = 1_800_000_000L, COIN = 5_000_000_000L;
    private static final byte[] TRUE = {0x51};
    @TempDir Path directory;
    private final JsonMapper json = JsonMapper.builder().build();
    private final NetworkParameters parameters = NetworkParametersRegistry.regtest();
    private final List<Block> history = new ArrayList<>();
    private final List<Scenario> scenarios = new ArrayList<>();
    private HttpClient http;
    private URI endpoint;
    private BlockHeader tip;
    private int tag;
    private record Scenario(String name, Block block, boolean valid) { }

    @Test void blockCompositionAndAcceptanceMatchCore32() throws Exception {
        Path coreData = Files.createDirectory(directory.resolve("core"));
        int port; try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        endpoint = URI.create("http://127.0.0.1:" + port);
        var core = new ProcessBuilder(System.getProperty("bitcoin.core.binary"), "-regtest", "-datadir=" + coreData,
                "-server", "-listen=0", "-connect=0", "-dnsseed=0", "-discover=0", "-listenonion=0", "-natpmp=0",
                "-rpcuser=test", "-rpcpassword=test-password", "-rpcport=" + port, "-assumevalid=0", "-acceptnonstdtxn=0")
                .redirectErrorStream(true).redirectOutput(directory.resolve("core.log").toFile()).start();
        try (var client = HttpClient.newHttpClient(); var db = new RocksDbDatabase(directory.resolve("java"));
             var node = new NodeValidationService(db, parameters, () -> NOW, new Mempool())) {
            http = client;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (true) {
                try { rpc("getblockcount"); break; }
                catch (Exception e) { if (System.nanoTime() > deadline) throw e; Thread.sleep(100); }
            }
            int version = ((Number) ((Map<?, ?>) rpc("getnetworkinfo")).get("version")).intValue();
            assertTrue(version >= 320100 && version < 330000, "Core 32.1 baseline required");
            rpc("setmocktime", NOW);
            rpc("generatetodescriptor", 101, "raw(51)");
            for (int height = 1; height <= 101; height++) {
                var hash = rpc("getblockhash", height);
                var block = BlockParser.parse(HexFormat.of().parseHex((String) rpc("getblock", hash, 0)));
                history.add(block); node.processBlock(block);
            }
            tip = history.getLast().header();
            buildScenarios();
            assertTrue(scenarios.size() >= 50);
            assertAll(scenarios.stream().map(scenario -> (org.junit.jupiter.api.function.Executable) () -> compare(node, scenario)));
            proofOfWorkSubmission(node);
            System.out.println("CORE32_BLOCK_CONSENSUS scenarios=" + scenarios.size());
            rpc("stop");
            assertTrue(core.waitFor(15, TimeUnit.SECONDS), "Core must shut down cleanly");
        } finally {
            if (core.isAlive()) try (var shutdownClient = HttpClient.newHttpClient()) {
                http = shutdownClient;
                rpc("stop");
            } catch (Exception ignored) { }
            if (!core.waitFor(15, TimeUnit.SECONDS)) { core.destroyForcibly(); core.waitFor(5, TimeUnit.SECONDS); }
        }
    }

    private void compare(NodeValidationService node, Scenario scenario) throws Exception {
        Block block = scenario.block();
        String hex = HexFormat.of().formatHex(BlockSerializer.serialize(block));
        Object reference = rpc("getblocktemplate", Map.of("mode", "proposal", "data", hex, "rules", List.of("segwit")));
        var actual = node.validateBlockProposal(block);
        // A mutated submission may leave only its header in Core's index. GBT
        // then reports duplicate-inconclusive without checking the repaired body;
        // submitblock below must still accept that body with the same header.
        boolean repairedKnownHeader = scenario.name().equals("valid-body-after-witness-mutation")
                && "duplicate-inconclusive".equals(reference);
        assertEquals(scenario.valid(), reference == null || repairedKnownHeader, scenario.name() + ": Core proposal=" + reference);
        assertEquals(scenario.valid(), actual.valid(), scenario.name() + ": Java proposal=" + actual.rejectReason());
        // Mine valid-target headers even for invalid bodies: this exercises actual
        // acceptance instead of allowing a missing PoW to hide a body-rule error.
        if (block.header().bits().equals(tip.bits()))
            block = NonceMiner.search(block, parameters, 0, 10_000, () -> false).orElseThrow();
        Object accepted = rpc("submitblock", HexFormat.of().formatHex(BlockSerializer.serialize(block)));
        assertEquals(scenario.valid(), accepted == null, scenario.name() + ": Core submit=" + accepted);
        boolean javaAccepted;
        String rejection = null;
        try { node.processBlock(block); javaAccepted = true; }
        catch (BlockValidationException | BlockHeaderValidationException | TransactionValidationException
               | ScriptExecutionException | ScriptParseException | IllegalArgumentException e) {
            javaAccepted = false; rejection = e.getMessage();
        }
        try {
            assertEquals(scenario.valid(), javaAccepted, scenario.name() + ": Java submit=" + rejection);
            if (scenario.valid()) assertEquals(block.hash(), node.activeTip().hash());
            System.out.println("CORE32_BLOCK " + scenario.name() + " accepted=" + javaAccepted);
        } finally {
            if (accepted == null) rpc("invalidateblock", block.hash().toDisplayHex());
            if (javaAccepted) node.invalidateBlock(block.hash());
            assertEquals(tip.hash(), node.activeTip().hash(), "Java case isolation");
            assertEquals(tip.hash().toDisplayHex(), rpc("getbestblockhash"), "Core case isolation");
        }
    }

    private void buildScenarios() {
        add("empty-template", candidate(List.of(), 0), true);
        for (int version : new int[]{0, 1, 2, 3, 4, -1, Integer.MIN_VALUE}) {
            var tx = spend(fund(1), COIN - 100, TRUE, version, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of()));
            add("transaction-version-" + version, candidate(List.of(tx), 100), true);
        }
        for (int version : new int[]{1, 2, 3, 4, 0x20000000, -1})
            add("block-version-" + version, header(candidate(List.of(), 0), version, tip.timestamp().value() + 1, tip.bits()), version >= 4);
        long mtp = history.get(history.size() - 6).header().timestamp().value();
        add("time-equal-mtp", header(candidate(List.of(), 0), 4, mtp, tip.bits()), false);
        add("time-after-mtp", header(candidate(List.of(), 0), 4, mtp + 1, tip.bits()), true);
        add("future-exact-limit", header(candidate(List.of(), 0), 4, NOW + 7200, tip.bits()), true);
        add("future-past-limit", header(candidate(List.of(), 0), 4, NOW + 7201, tip.bits()), false);
        add("wrong-difficulty", header(candidate(List.of(), 0), 4, tip.timestamp().value() + 1, new UInt32(0x207ffffeL)), false);
        var base = candidate(List.of(), 0);
        add("no-transactions", body(base, List.of()), false);
        add("multiple-coinbase", body(base, List.of(base.transactions().getFirst(), candidate(List.of(), 0).transactions().getFirst())), false);
        var cb = base.transactions().getFirst();
        add("coinbase-overpay-one-sat", body(base, List.of(coinbase(cb, cb.inputs().getFirst(), COIN + 1))), false);
        add("coinbase-underpay", body(base, List.of(coinbase(cb, cb.inputs().getFirst(), COIN - 1))), true);
        add("coinbase-wrong-height", candidateAtHeight(103), false);
        for (int length : new int[]{1, 100, 101}) {
            byte[] script = new byte[length]; script[0] = 1;
            if (length > 1) script[1] = 102;
            var input = new TxIn(OutPoint.coinbase(), script, TxIn.FINAL_SEQUENCE, cb.inputs().getFirst().witness());
            add("coinbase-script-length-" + length, body(base, List.of(coinbase(cb, input, COIN))), length == 100);
        }
        var tx = spend(fund(2), COIN - 100, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of()));
        add("coinbase-maturity-exact-100", candidate(List.of(tx), 100), true);
        tx = spend(fund(3), COIN - 100, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of()));
        add("coinbase-maturity-99", candidate(List.of(tx), 100), false);
        tx = spend(new OutPoint(Hash256.fromDisplayHex("12".repeat(32)), new UInt32(0)), 0, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of()));
        add("missing-prevout", candidate(List.of(tx), 0), false);
        tx = spend(fund(1), COIN + 1, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of()));
        add("inputs-below-outputs", candidate(List.of(tx), 0), false);
        tx = spend(fund(1), -1, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of()));
        add("negative-output", candidate(List.of(tx), 0), false);
        var doubleInput = new Transaction(2, List.of(new TxIn(fund(1), new byte[0], TxIn.FINAL_SEQUENCE),
                new TxIn(fund(1), new byte[0], TxIn.FINAL_SEQUENCE)), List.of(new TxOut(COIN - 100, TRUE)), new UInt32(0));
        add("duplicate-input", candidate(List.of(doubleInput), 100), false);
        var first = spend(fund(1), COIN - 100, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of()));
        var second = spend(fund(1), COIN - 101, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of()));
        add("block-double-spend", candidate(List.of(first, second), 201), false);
        add("duplicate-transaction", candidate(List.of(first, first), 200), false);
        var child = spend(new OutPoint(first.txId(), new UInt32(0)), COIN - 200, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of()));
        add("child-after-parent", candidate(List.of(first, child), 200), true);
        add("child-before-parent", candidate(List.of(child, first), 200), false);
        for (long lock : new long[]{101, 102, 103}) {
            tx = spend(fund(1), COIN - 100, TRUE, 2, new UInt32(0xfffffffeL), lock, new Witness(List.of()));
            add("height-lock-" + lock, candidate(List.of(tx), 100), lock < 102);
        }
        for (long lock : new long[]{mtp - 1, mtp, mtp + 1}) {
            tx = spend(fund(1), COIN - 100, TRUE, 2, new UInt32(0xfffffffeL), lock, new Witness(List.of()));
            add("time-lock-" + (lock - mtp), candidate(List.of(tx), 100), lock < mtp);
        }
        for (long sequence : new long[]{101, 102, 0x80000000L}) {
            tx = spend(fund(1), COIN - 100, TRUE, 2, new UInt32(sequence), 0, new Witness(List.of()));
            add("relative-height-" + sequence, candidate(List.of(tx), 100), sequence != 102);
        }
        for (long sequence : new long[]{0x400000L, 0x400001L}) {
            tx = spend(fund(2), COIN - 100, TRUE, 2, new UInt32(sequence), 0, new Witness(List.of()));
            add("relative-time-" + (sequence & 65535), candidate(List.of(tx), 100), sequence == 0x400000L);
        }
        tx = spend(fund(1), COIN - 100, TRUE, 1, new UInt32(65535), 0, new Witness(List.of()));
        add("v1-ignores-relative-lock", candidate(List.of(tx), 100), true);
        for (long sequence : new long[]{0, 1}) {
            tx = spend(new OutPoint(first.txId(), new UInt32(0)), COIN - 200, TRUE, 2, new UInt32(sequence), 0, new Witness(List.of()));
            add("same-block-relative-lock-" + sequence, candidate(List.of(first, tx), 200), sequence == 0);
        }
        for (int count : new int[]{20_000, 20_001}) {
            byte[] payout = new byte[count]; Arrays.fill(payout, (byte) 0xac);
            add("legacy-sigops-" + count, candidate(List.of(), 0, payout), count == 20_000);
        }
        var weightBase = candidate(List.of(), 0, new byte[]{0x6a});
        int size = 1 + (int) ((4_000_000 - BlockWeight.calculate(weightBase)) / 4);
        size -= (int) ((BlockWeight.calculate(candidate(List.of(), 0, new byte[size])) - 4_000_000) / 4);
        for (int length : new int[]{size, size + 1}) {
            byte[] payout = new byte[length]; payout[0] = 0x6a;
            var block = candidate(List.of(), 0, payout);
            add("stripped-weight-" + BlockWeight.calculate(block), block, length == size);
        }
        witnessScenarios();
        taprootScenarios();
        contextualSigopsScenarios();
    }

    private void witnessScenarios() {
        byte[] p2wsh = new byte[34]; p2wsh[1] = 32;
        System.arraycopy(ru.bitcoin.node.crypto.hash.Sha256.hash(TRUE), 0, p2wsh, 2, 32);
        var parent = spend(fund(1), COIN - 100, p2wsh, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of()));
        var point = new OutPoint(parent.txId(), new UInt32(0));
        var child = spend(point, COIN - 200, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of(TRUE)));
        add("p2wsh-valid", candidate(List.of(parent, child), 200), true);
        var bad = spend(point, COIN - 200, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of(new byte[]{0})));
        add("p2wsh-wrong-script", candidate(List.of(parent, bad), 200), false);
        var valid = candidate(List.of(parent, child), 200);
        var alteredChild = spend(point, COIN - 200, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of(new byte[]{0x52})));
        add("witness-merkle-mismatch", new Block(valid.header(), List.of(valid.transactions().getFirst(), parent, alteredChild)), false);
        var cb = valid.transactions().getFirst();
        var cbInput = cb.inputs().getFirst();
        var wrongReserve = new TxIn(cbInput.previousOutput(), cbInput.scriptSig(), cbInput.sequence(), new Witness(List.of(new byte[31])));
        add("witness-reserved-length", body(valid, List.of(coinbase(cb, wrongReserve, COIN + 200), parent, child)), false);
        add("valid-body-after-witness-mutation", valid, true);
        byte[] future = new byte[34]; future[0] = 0x52; future[1] = 32;
        parent = spend(fund(1), COIN - 100, future, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of()));
        point = new OutPoint(parent.txId(), new UInt32(0));
        child = spend(point, COIN - 200, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of(new byte[1000])));
        add("unknown-witness-zero-program", candidate(List.of(parent, child), 200), false);
        future[2] = 1;
        parent = spend(fund(1), COIN - 100, future, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of()));
        point = new OutPoint(parent.txId(), new UInt32(0));
        child = spend(point, COIN - 200, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of(new byte[1000])));
        add("unknown-witness-version-nonstandard", candidate(List.of(parent, child), 200), true);
        int payloadSize = 200_000;
        child = spend(point, COIN - 200, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of(new byte[payloadSize])));
        long baseWeight = BlockWeight.calculate(candidate(List.of(parent, child), 200));
        payloadSize += (int) (4_000_000 - baseWeight);
        for (int length : new int[]{payloadSize, payloadSize + 1}) {
            child = spend(point, COIN - 200, TRUE, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of(new byte[length])));
            var block = candidate(List.of(parent, child), 200);
            add("witness-weight-" + BlockWeight.calculate(block), block, length == payloadSize);
        }
    }

    private void contextualSigopsScenarios() {
        // Twenty CHECKSIGs in an unexecuted branch still consume static sigops.
        byte[] redeem = new byte[24]; redeem[0] = 0; redeem[1] = 0x63;
        Arrays.fill(redeem, 2, 22, (byte) 0xac); redeem[22] = 0x68; redeem[23] = 0x51;
        for (boolean witness : new boolean[]{false, true}) {
            byte[] locking;
            if (witness) {
                locking = new byte[34]; locking[1] = 32;
                System.arraycopy(ru.bitcoin.node.crypto.hash.Sha256.hash(redeem), 0, locking, 2, 32);
            } else {
                locking = new byte[23]; locking[0] = (byte) 0xa9; locking[1] = 20; locking[22] = (byte) 0x87;
                System.arraycopy(ru.bitcoin.node.crypto.hash.Hash160.hash(redeem), 0, locking, 2, 20);
            }
            int limit = witness ? 4000 : 1000;
            for (int count : new int[]{limit, limit + 1}) {
                var outputs = new ArrayList<TxOut>();
                for (int i = 0; i < count; i++) outputs.add(new TxOut(i == 0 ? COIN - 100 : 0, locking));
                var parent = new Transaction(2, List.of(new TxIn(fund(1), new byte[0], TxIn.FINAL_SEQUENCE)), outputs, new UInt32(0));
                var inputs = new ArrayList<TxIn>();
                byte[] scriptSig = new byte[redeem.length + 1]; scriptSig[0] = (byte) redeem.length;
                System.arraycopy(redeem, 0, scriptSig, 1, redeem.length);
                for (int i = 0; i < count; i++) inputs.add(new TxIn(new OutPoint(parent.txId(), new UInt32(i)),
                        witness ? new byte[0] : scriptSig, TxIn.FINAL_SEQUENCE, new Witness(witness ? List.of(redeem) : List.of())));
                var child = new Transaction(2, inputs, List.of(new TxOut(COIN - 200, TRUE)), new UInt32(0));
                add((witness ? "p2wsh" : "p2sh") + "-sigops-" + count, candidate(List.of(parent, child), 200), count == limit);
            }
        }
    }

    private void proofOfWorkSubmission(NodeValidationService node) throws Exception {
        var block = candidate(List.of(), 0);
        var h = block.header();
        long nonce = 0;
        while (ru.bitcoin.node.consensus.pow.ProofOfWork.isValid(h, parameters))
            h = new BlockHeader(h.version(), h.previousBlockHash(), h.merkleRoot(), h.timestamp(), h.bits(), new UInt32(++nonce));
        var unsolved = new Block(h, block.transactions());
        String hex = HexFormat.of().formatHex(BlockSerializer.serialize(unsolved));
        assertNull(rpc("getblocktemplate", Map.of("mode", "proposal", "data", hex)));
        assertTrue(node.validateBlockProposal(unsolved).valid());
        assertNotNull(rpc("submitblock", hex));
        assertThrows(BlockHeaderValidationException.class, () -> node.processBlock(unsolved));
        System.out.println("CORE32_BLOCK proof-of-work-required-on-submission");
    }

    private void taprootScenarios() {
        byte[][] scripts = { {0x51}, {0}, {0x50}, {0, 0x63, 0x50, 0x68}, {0x4c},
                {0x63, 0x51, 0x67, 0x51, 0x68}, {0x51}, {0x51}, {0x00} };
        String[] names = {"true", "false", "op-success", "op-success-unexecuted", "truncated-push",
                "minimal-if", "cleanstack", "annex", "future-leaf-version"};
        boolean[] valid = {true, false, true, true, false, false, false, true, true};
        var generator = ru.bitcoin.node.crypto.secp256k1.Secp256k1.DOMAIN.getG().normalize();
        byte[] internal = Arrays.copyOfRange(generator.getEncoded(true), 1, 33);
        for (int i = 0; i < scripts.length; i++) {
            byte[] script = scripts[i];
            byte leafVersion = (byte) (i == 8 ? 0xc2 : 0xc0);
            byte[] leaf = ru.bitcoin.node.crypto.hash.TaggedHash.hash("TapLeaf", new byte[]{leafVersion},
                    ru.bitcoin.node.common.encoding.CompactSize.encode(script.length), script);
            var tweak = new java.math.BigInteger(1, ru.bitcoin.node.crypto.hash.TaggedHash.hash("TapTweak", internal, leaf));
            byte[] output = generator.add(generator.multiply(tweak)).normalize().getEncoded(true);
            byte[] program = new byte[34]; program[0] = 0x51; program[1] = 32;
            System.arraycopy(output, 1, program, 2, 32);
            byte[] control = new byte[33]; control[0] = (byte) (leafVersion | (output[0] & 1));
            System.arraycopy(internal, 0, control, 1, 32);
            var parent = spend(fund(1), COIN - 100, program, 2, TxIn.FINAL_SEQUENCE, 0, new Witness(List.of()));
            var witness = new ArrayList<byte[]>();
            if (i == 5) witness.add(new byte[]{2}); // MINIMALIF is consensus in tapscript.
            if (i == 6) witness.add(new byte[]{1});
            witness.add(script); witness.add(control);
            if (i == 7) witness.add(new byte[]{0x50, 1, 2});
            var child = spend(new OutPoint(parent.txId(), new UInt32(0)), COIN - 200, TRUE, 2,
                    TxIn.FINAL_SEQUENCE, 0, new Witness(witness));
            add("tapscript-" + names[i], candidate(List.of(parent, child), 200), valid[i]);
        }
    }

    private Transaction spend(OutPoint point, long value, byte[] script, int version, UInt32 sequence, long lock, Witness witness) {
        return new Transaction(version, List.of(new TxIn(point, new byte[0], sequence, witness)), List.of(new TxOut(value, script)), new UInt32(lock));
    }
    private OutPoint fund(int height) { return new OutPoint(history.get(height - 1).transactions().getFirst().txId(), new UInt32(0)); }
    private void add(String name, Block block, boolean valid) { scenarios.add(new Scenario(name, block, valid)); }
    private Block candidate(List<Transaction> txs, long fee) { return candidate(txs, fee, TRUE); }
    private Block candidate(List<Transaction> txs, long fee, byte[] payout) {
        var cb = CoinbaseBuilder.build(102, parameters, fee, payout, new byte[]{0, (byte) ++tag}, txs);
        var transactions = new ArrayList<Transaction>(); transactions.add(cb); transactions.addAll(txs);
        return body(new Block(new BlockHeader(4, tip.hash(), tip.merkleRoot(), new UInt32(tip.timestamp().value() + 1), tip.bits(), new UInt32(0)), List.of()), transactions);
    }
    private Block candidateAtHeight(long height) {
        var cb = CoinbaseBuilder.build(height, parameters, 0, TRUE, new byte[]{0, (byte) ++tag}, List.of());
        return body(candidate(List.of(), 0), List.of(cb));
    }
    private Transaction coinbase(Transaction original, TxIn input, long value) {
        var outputs = new ArrayList<>(original.outputs()); outputs.set(0, new TxOut(value, outputs.getFirst().scriptPubKey()));
        return new Transaction(original.version(), List.of(input), outputs, original.lockTime());
    }
    private Block body(Block original, List<Transaction> transactions) {
        var h = original.header();
        var root = transactions.isEmpty() ? new Hash256(new byte[32]) : MerkleTree.calculateRoot(transactions.stream().map(Transaction::txId).toList());
        return new Block(new BlockHeader(h.version(), h.previousBlockHash(), root, h.timestamp(), h.bits(), h.nonce()), transactions);
    }
    private Block header(Block block, int version, long time, UInt32 bits) {
        var h = block.header();
        return new Block(new BlockHeader(version, h.previousBlockHash(), h.merkleRoot(), new UInt32(time), bits, h.nonce()), block.transactions());
    }
    private Object rpc(String method, Object... params) throws Exception {
        var request = HttpRequest.newBuilder(endpoint).header("Authorization", "Basic " + Base64.getEncoder()
                .encodeToString("test:test-password".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("id", 1, "method", method, "params", Arrays.asList(params))))).build();
        var reply = json.readValue(http.send(request, HttpResponse.BodyHandlers.ofString()).body(), Map.class);
        if (reply.get("error") != null) throw new IllegalStateException(method + ": " + reply.get("error"));
        return reply.get("result");
    }
}
