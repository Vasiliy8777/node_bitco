package ru.bitcoin.node.app.rpc;

import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.consensus.pow.CompactTarget;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.protocol.serialization.*;
import ru.bitcoin.node.common.types.Hash256;

import java.util.*;
import java.util.function.BooleanSupplier;

/** Mining RPC backed by a coherent, contextually checked chain/mempool snapshot. */
public final class MiningController {
    private final NodeValidationService validation;
    private final NodeRelayService relay;
    private final NetworkParameters parameters;
    private final BooleanSupplier ready;
    private final byte[] payout;
    private final long maximumWeight;
    private final FeeRate minimumFee;
    private volatile boolean closed;

    public MiningController(NodeValidationService validation, NodeRelayService relay, NetworkParameters parameters,
                            BooleanSupplier ready, byte[] payout, long maximumWeight, FeeRate minimumFee) {
        this.validation = validation;
        this.relay = relay;
        this.parameters = parameters;
        this.ready = ready;
        this.payout = payout.clone();
        if (payout.length == 0 || payout.length > 10_000) throw new IllegalArgumentException("Configure a valid payout script");
        if (maximumWeight <= 0 || maximumWeight > 4_000_000) throw new IllegalArgumentException("Invalid mining weight limit");
        this.maximumWeight = maximumWeight;
        this.minimumFee = minimumFee;
    }

    public Map<String, Object> getBlockTemplate(Map<String, Object> request) throws InterruptedException {
        if (!"template".equals(request.getOrDefault("mode", "template")))
            throw new RpcException(-8, "Only template mode is supported");
        if (!(request.get("rules") instanceof List<?> rules) || !rules.contains("segwit"))
            throw new RpcException(-8, "Client must support segwit (rules: [segwit])");
        ensureReady();
        String previous = request.get("longpollid") instanceof String id ? id : null;
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
        while (previous != null && previous.equals(longPollId()) && System.nanoTime() < deadline) {
            ensureReady();
            validation.awaitRevision(validation.revision(), 1_000);
        }
        ensureReady();
        var snapshot = validation.miningSnapshot(payout, new byte[8], maximumWeight, minimumFee);
        ensureReady();
        var block = snapshot.block();
        Map<Hash256, MempoolEntry> entries = new HashMap<>();
        snapshot.entries().forEach(entry -> entries.put(entry.transaction().txId(), entry));
        Map<Hash256, Integer> positions = new HashMap<>();
        List<Map<String, Object>> transactions = new ArrayList<>();
        for (var tx : block.transactions().subList(1, block.transactions().size())) {
            var entry = Objects.requireNonNull(entries.get(tx.txId()));
            var depends = tx.inputs().stream().map(input -> positions.get(input.previousOutput().transactionId()))
                    .filter(Objects::nonNull).distinct().sorted().toList();
            transactions.add(Map.of("data", HexFormat.of().formatHex(TransactionSerializer.serialize(tx)),
                    "txid", tx.txId().toDisplayHex(), "hash", tx.wtxId().toDisplayHex(),
                    "depends", depends, "fee", entry.fee(), "sigops", entry.sigOpCost(), "weight", TransactionWeight.calculate(tx)));
            positions.put(tx.txId(), positions.size() + 1);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("version", block.header().version());
        result.put("rules", List.of("csv", "!segwit", "taproot"));
        result.put("vbavailable", Map.of());
        result.put("vbrequired", 0);
        result.put("previousblockhash", block.header().previousBlockHash().toDisplayHex());
        result.put("transactions", transactions);
        result.put("coinbaseaux", Map.of());
        result.put("coinbasevalue", block.transactions().getFirst().outputs().stream().mapToLong(output -> output.value()).sum());
        result.put("longpollid", block.header().previousBlockHash().toDisplayHex() + ":" + snapshot.revision());
        result.put("target", String.format("%064x", CompactTarget.decode(block.header().bits().value())));
        result.put("mintime", snapshot.medianTimePast() + 1);
        result.put("mutable", List.of("time", "transactions", "prevblock"));
        result.put("noncerange", "00000000ffffffff");
        result.put("sigoplimit", 80_000);
        result.put("sizelimit", 4_000_000);
        result.put("weightlimit", maximumWeight);
        result.put("curtime", block.header().timestamp().value());
        result.put("bits", String.format("%08x", block.header().bits().value()));
        result.put("height", snapshot.height());
        result.put("submitold", previous != null && previous.startsWith(block.header().previousBlockHash().toDisplayHex() + ":"));
        var coinbase = block.transactions().getFirst();
        if (coinbase.outputs().size() > 1)
            result.put("default_witness_commitment", HexFormat.of().formatHex(coinbase.outputs().getLast().scriptPubKey()));
        return result;
    }

    private String longPollId() { return validation.activeTip().hash().toDisplayHex() + ":" + validation.revision(); }

    private void ensureReady() {
        if (closed || !ready.getAsBoolean()) throw new RpcException(-10, "Node is not synchronized or has no peers");
        if (validation.activeTip().chainWork().compareTo(parameters.minimumChainWork()) < 0)
            throw new RpcException(-10, "Active chain is below minimum chain work");
        if (parameters.network() == BitcoinNetwork.SIGNET) throw new RpcException(-8, "Signet mining requires challenge signing");
        if (parameters.network() != BitcoinNetwork.REGTEST
                && validation.activeTip().header().timestamp().value() < java.time.Instant.now().getEpochSecond() - 7200)
            throw new RpcException(-10, "Active chain is too old for mining");
    }

    public String submitBlock(String hex) {
        final ru.bitcoin.node.protocol.block.Block block;
        try { block = BlockParser.parse(HexFormat.of().parseHex(hex)); }
        catch (IllegalArgumentException exception) { throw new RpcException(-22, "Block decode failed"); }
        try {
            return switch (relay.submitBlock(block)) {
                case CONNECTED -> null;
                case ALREADY_IN_ACTIVE_CHAIN -> "duplicate";
                case UNKNOWN_PARENT -> "prev-blk-not-found";
                case STORED_SIDE_CHAIN_CONTEXT_PENDING -> "inconclusive";
            };
        } catch (ru.bitcoin.node.consensus.block.BlockValidationException
                 | ru.bitcoin.node.consensus.block.BlockHeaderValidationException
                 | ru.bitcoin.node.consensus.transaction.TransactionValidationException
                 | ru.bitcoin.node.script.ScriptExecutionException | ru.bitcoin.node.script.ScriptParseException exception) {
            return exception.getMessage();
        }
    }

    public void close() { closed = true; }
}
