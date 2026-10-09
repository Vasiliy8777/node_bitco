package ru.bitcoin.node.app.rpc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.protocol.serialization.TransactionParser;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.serialization.BlockHeaderSerializer;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.p2p.PeerManager;
import ru.bitcoin.node.p2p.message.VersionMessage;
import ru.bitcoin.node.mempool.MempoolLimits;
import ru.bitcoin.node.script.UnspendableScript;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Local authenticated JSON-RPC endpoint, opt-in through bitcoin.rpc.enabled.
 */
public final class NodeRpcServer implements AutoCloseable {
    private static final int MAX_REQUEST = 8_100_000;
    private static final long DEFAULT_MAX_RAW_TX_FEE_RATE = 10_000_000L; // 0.10 BTC/kvB
    private static final long MAX_RAW_TX_FEE_RATE = 100_000_000L; // 1 BTC/kvB
    private static final long DEFAULT_MAX_BURN_AMOUNT = 0L;
    private final HttpServer server;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("bitcoin-rpc-", 0).factory());
    private final Semaphore longPolls = new Semaphore(4);
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final byte[] authorization;
    private final MiningController mining;
    private final NodeValidationService validation;
    private final NodeRelayService relay;
    private final NodeSyncInfrastructure sync;
    private final BooleanSupplier ready;
    private final PeerManager peerManager;
    private final long localServices;
    private final long startedNanos = System.nanoTime();
    private final ConcurrentHashMap<Long, ActiveCommand> activeCommands = new ConcurrentHashMap<>();

    public NodeRpcServer(InetSocketAddress address, String user, String password, MiningController mining,
                         NodeValidationService validation, NodeRelayService relay, NodeSyncInfrastructure sync,
                         BooleanSupplier ready) throws IOException {
        this(address, user, password, mining, validation, relay, sync, ready, null, VersionMessage.DEFAULT_SERVICES);
    }

    public NodeRpcServer(InetSocketAddress address, String user, String password, MiningController mining,
                         NodeValidationService validation, NodeRelayService relay, NodeSyncInfrastructure sync,
                         BooleanSupplier ready, PeerManager peerManager) throws IOException {
        this(address, user, password, mining, validation, relay, sync, ready, peerManager, VersionMessage.DEFAULT_SERVICES);
    }

    public NodeRpcServer(InetSocketAddress address, String user, String password, MiningController mining,
                         NodeValidationService validation, NodeRelayService relay, NodeSyncInfrastructure sync,
                         BooleanSupplier ready, PeerManager peerManager, long localServices) throws IOException {
        if (address.isUnresolved() || !address.getAddress().isLoopbackAddress())
            throw new IllegalArgumentException("RPC must bind to a loopback address");
        if (user.isBlank() || user.contains(":") || password.isBlank())
            throw new IllegalArgumentException("RPC username and password are required");
        authorization = ("Basic " + Base64.getEncoder().encodeToString((user + ":" + password)
                .getBytes(StandardCharsets.UTF_8))).getBytes(StandardCharsets.US_ASCII);
        this.mining = mining;
        this.validation = validation;
        this.relay = relay;
        this.sync = sync;
        this.ready = ready;
        this.peerManager = peerManager;
        this.localServices = localServices;
        server = HttpServer.create(address, 16);
        server.createContext("/", this::handle);
        server.setExecutor(executor);
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            if (auth == null || !MessageDigest.isEqual(authorization, auth.getBytes(StandardCharsets.US_ASCII))) {
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=bitcoin-node");
                respond(exchange, 401, Map.of("error", "Unauthorized"));
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                respond(exchange, 405, Map.of("error", "POST required"));
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST + 1);
            if (body.length > MAX_REQUEST) {
                respond(exchange, 413, Map.of("error", "Request too large"));
                return;
            }

            final Object root;
            try {
                root = mapper.readValue(body, Object.class);
            } catch (RuntimeException exception) {
                respond(exchange, 200, rpcError(null, null, -32700, "Parse error"));
                return;
            }

            if (root instanceof Map<?, ?> request) {
                RpcReply reply = executeRequest(request);
                if (reply.notification()) {
                    respondNoContent(exchange);
                } else {
                    respond(exchange, 200, reply.body());
                }
                return;
            }
            if (root instanceof List<?> batch) {
                if (batch.isEmpty()) {
                    // Bitcoin Core preserves the historical empty-batch response.
                    respond(exchange, 200, List.of());
                    return;
                }
                List<Object> replies = new ArrayList<>();
                for (Object item : batch) {
                    if (!(item instanceof Map<?, ?> request)) {
                        replies.add(rpcError(null, "2.0", -32600, "Invalid Request object"));
                        continue;
                    }
                    RpcReply reply = executeRequest(request);
                    if (!reply.notification()) replies.add(reply.body());
                }
                if (replies.isEmpty()) respondNoContent(exchange);
                else respond(exchange, 200, replies);
                return;
            }
            respond(exchange, 200, rpcError(null, null, -32700, "Top-level object parse error"));
        }
    }

    private RpcReply executeRequest(Map<?, ?> request) {
        Object id = request.get("id");
        String jsonVersion = request.get("jsonrpc") instanceof String value ? value : null;
        boolean v2 = "2.0".equals(jsonVersion);
        boolean notification = v2 && !request.containsKey("id");
        Object result = null;
        Object error = null;
        boolean acquired = false;
        String method = null;
        long started = System.nanoTime();
        try {
            if (request.containsKey("jsonrpc") && !v2)
                throw new RpcException(-32600, "Invalid JSON-RPC version");
            if (!(request.get("method") instanceof String value))
                throw new RpcException(-32600, "Invalid request");
            method = value;
            if (id != null && !(id instanceof String) && !(id instanceof Number))
                throw new RpcException(-32600, "Invalid request id");
            Object rawParams = request.get("params");
            if (rawParams != null && !(rawParams instanceof List<?>))
                throw new RpcException(-32602, "Expected positional parameters");
            List<?> params = rawParams == null ? List.of() : (List<?>) rawParams;
            if (method.equals("getblocktemplate") && !params.isEmpty() && params.getFirst() instanceof Map<?, ?> options
                    && options.containsKey("longpollid")) {
                acquired = longPolls.tryAcquire();
                if (!acquired) throw new RpcException(-8, "Too many long-poll requests");
            }
            activeCommands.put(Thread.currentThread().threadId(), new ActiveCommand(method, started));
            result = dispatch(method, params);
        } catch (RpcException exception) {
            error = Map.of("code", exception.code(), "message", exception.getMessage());
        } catch (IllegalArgumentException exception) {
            error = Map.of("code", -32602, "message", "Invalid parameters");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            error = Map.of("code", -32603, "message", "Server stopping");
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(NodeRpcServer.class).error("RPC failed", exception);
            error = Map.of("code", -32603, "message", "Internal error");
        } finally {
            activeCommands.remove(Thread.currentThread().threadId());
            if (acquired) longPolls.release();
        }
        if (notification) return new RpcReply(null, true);
        return new RpcReply(rpcResponse(id, jsonVersion, result, error), false);
    }

    private static Map<String, Object> rpcResponse(Object id, String jsonVersion, Object result, Object error) {
        var response = new LinkedHashMap<String, Object>();
        if ("2.0".equals(jsonVersion)) {
            response.put("jsonrpc", "2.0");
            if (error == null) response.put("result", result);
            else response.put("error", error);
        } else {
            response.put("result", result);
            response.put("error", error);
        }
        response.put("id", id);
        return response;
    }

    private static Map<String, Object> rpcError(Object id, String jsonVersion, int code, String message) {
        return rpcResponse(id, jsonVersion, null, Map.of("code", code, "message", message));
    }

    private record RpcReply(Object body, boolean notification) { }
    private record ActiveCommand(String method, long startedNanos) { }

    @SuppressWarnings("unchecked")
    private Object dispatch(String method, List<?> params) throws InterruptedException {
        return switch (method) {
            case "help" -> {
                if (params.size() > 1 || (!params.isEmpty() && !(params.getFirst() instanceof String)))
                    throw new RpcException(-32602, "help takes an optional command name");
                String command = params.isEmpty() ? null : (String) params.getFirst();
                yield rpcHelp(command);
            }
            case "getrpcinfo" -> {
                requireNoParams(method, params);
                long now = System.nanoTime();
                yield Map.of("active_commands", activeCommands.values().stream()
                        .map(command -> Map.of("method", command.method(),
                                "duration", Math.max(0L, (now - command.startedNanos()) / 1_000L)))
                        .toList());
            }
            case "ping" -> {
                requireNoParams(method, params);
                requirePeerManager().requestPings();
                yield null;
            }
            case "savemempool" -> {
                requireNoParams(method, params);
                validation.flushPersistentMempool();
                yield Map.of("filename", "rocksdb:mempool");
            }
            case "getblocktemplate" -> {
                if (params.size() != 1 || !(params.getFirst() instanceof Map<?, ?>))
                    throw new RpcException(-32602, "Expected one template request object");
                yield mining.handleBlockTemplate((Map<String, Object>) params.getFirst());
            }
            case "submitblock" -> mining.submitBlock(stringParam(params));
            case "sendrawtransaction" -> {
                if (params.isEmpty() || params.size() > 3 || !(params.getFirst() instanceof String raw))
                    throw new RpcException(-32602, "Expected raw transaction and optional maxfeerate/maxburnamount");
                long maxFeeRate = parseMaxFeeRate(params, 1);
                long maxBurnAmount = parseMoneyParam(params, 2, DEFAULT_MAX_BURN_AMOUNT, "maxburnamount");
                try {
                    Transaction transaction = TransactionParser.parse(HexFormat.of().parseHex(raw));
                    var probe = validation.testMempoolAccept(List.of(transaction));
                    if (probe.allowed()) {
                        var entry = probe.entries().getFirst();
                        if (maxFeeRate != 0 && entry.modifiedFee() * 1000L > maxFeeRate * entry.virtualSize())
                            throw new RpcException(-25, "Fee exceeds maximum configured by user (e.g. -maxtxfee, maxfeerate)");
                        if (burnAmount(transaction) > maxBurnAmount)
                            throw new RpcException(-25, "Unspendable output exceeds maximum configured by user (maxburnamount)");
                    }
                    yield relay.submitTransaction(transaction).toDisplayHex();
                } catch (ru.bitcoin.node.mempool.MempoolAdmissionException
                         | ru.bitcoin.node.consensus.transaction.TransactionValidationException
                         | ru.bitcoin.node.script.ScriptExecutionException |
                         ru.bitcoin.node.script.ScriptParseException exception) {
                    throw new RpcException(-26, exception.getMessage());
                }
            }
            case "testmempoolaccept" -> {
                if (params.isEmpty() || params.size() > 2 || !(params.getFirst() instanceof List<?> rawTransactions))
                    throw new RpcException(-32602, "Expected rawtxs array and optional maxfeerate");
                long maxFeeRate = parseMaxFeeRate(params, 1);
                if (rawTransactions.isEmpty() || rawTransactions.size() > 25)
                    throw new RpcException(-8, "Array must contain between 1 and 25 transactions");
                List<Transaction> transactions = new ArrayList<>(rawTransactions.size());
                try {
                    for (Object raw : rawTransactions) {
                        if (!(raw instanceof String hex)) throw new RpcException(-32602, "Transactions must be hex strings");
                        transactions.add(TransactionParser.parse(HexFormat.of().parseHex(hex)));
                    }
                } catch (IllegalArgumentException | java.nio.BufferUnderflowException exception) {
                    throw new RpcException(-22, "TX decode failed");
                }
                var probe = validation.testMempoolAccept(transactions);
                if (probe.allowed()) {
                    for (int i = 0; i < transactions.size(); i++) {
                        var entry = probe.entries().get(i);
                        if (maxFeeRate != 0 && entry.modifiedFee() * 1000L > maxFeeRate * entry.virtualSize())
                            yield rejectedTestMempoolAccept(transactions, "max-fee-exceeded");
                    }
                }
                List<Map<String, Object>> results = new ArrayList<>(transactions.size());
                if (!probe.allowed()) {
                    for (Transaction tx : transactions) {
                        var result = new LinkedHashMap<String, Object>();
                        result.put("txid", tx.txId().toDisplayHex());
                        result.put("wtxid", tx.wtxId().toDisplayHex());
                        result.put("allowed", false);
                        if (transactions.size() > 1) result.put("package-error", probe.rejectReason());
                        else result.put("reject-reason", probe.rejectReason());
                        results.add(result);
                    }
                    yield results;
                }
                long packageFee = probe.entries().stream().mapToLong(ru.bitcoin.node.mempool.MempoolEntry::modifiedFee).sum();
                long packageVsize = probe.entries().stream().mapToLong(ru.bitcoin.node.mempool.MempoolEntry::virtualSize).sum();
                double effectiveRate = packageVsize == 0 ? 0.0 : ((double) packageFee * 1000.0 / packageVsize) / 100_000_000.0;
                List<String> includes = transactions.stream().map(tx -> tx.wtxId().toDisplayHex()).toList();
                for (int i = 0; i < transactions.size(); i++) {
                    Transaction tx = transactions.get(i);
                    var entry = probe.entries().get(i);
                    var result = new LinkedHashMap<String, Object>();
                    result.put("txid", tx.txId().toDisplayHex());
                    result.put("wtxid", tx.wtxId().toDisplayHex());
                    result.put("allowed", true);
                    result.put("vsize", entry.virtualSize());
                    result.put("vsize_adjusted", entry.virtualSize());
                    result.put("vsize_bip141", ru.bitcoin.node.consensus.transaction.TransactionWeight.virtualSize(entry.weight()));
                    var fees = new LinkedHashMap<String, Object>();
                    fees.put("base", satoshisToBtc(entry.fee()));
                    fees.put("effective-feerate", effectiveRate);
                    fees.put("effective-includes", includes);
                    result.put("fees", fees);
                    results.add(result);
                }
                yield results;
            }
            case "submitpackage" -> {
                if (params.isEmpty() || params.size() > 3 || !(params.getFirst() instanceof List<?> rawPackage))
                    throw new RpcException(-32602, "Expected package array and optional maxfeerate/maxburnamount");
                long maxFeeRate = parseMaxFeeRate(params, 1);
                long maxBurnAmount = parseMoneyParam(params, 2, DEFAULT_MAX_BURN_AMOUNT, "maxburnamount");
                if (rawPackage.isEmpty() || rawPackage.size() > 25)
                    throw new RpcException(-8, "Array must contain between 1 and 25 transactions");
                List<Transaction> transactions = new ArrayList<>(rawPackage.size());
                try {
                    for (Object raw : rawPackage) {
                        if (!(raw instanceof String hex)) throw new RpcException(-32602, "Package transactions must be hex strings");
                        transactions.add(TransactionParser.parse(HexFormat.of().parseHex(hex)));
                    }
                } catch (IllegalArgumentException | java.nio.BufferUnderflowException exception) {
                    throw new RpcException(-22, "TX decode failed");
                }
                try {
                    var probe = validation.testMempoolAccept(transactions);
                    if (!probe.allowed()) throw new ru.bitcoin.node.mempool.MempoolAdmissionException(probe.rejectReason());
                    for (int i = 0; i < transactions.size(); i++) {
                        var entry = probe.entries().get(i);
                        if (maxFeeRate != 0 && entry.modifiedFee() * 1000L > maxFeeRate * entry.virtualSize())
                            throw new RpcException(-25, "Fee exceeds maximum configured by user (e.g. -maxtxfee, maxfeerate)");
                        if (burnAmount(transactions.get(i)) > maxBurnAmount)
                            throw new RpcException(-25, "Unspendable output exceeds maximum configured by user (maxburnamount)");
                    }
                    var submission = relay.submitPackage(transactions);
                    var accepted = submission.entries();
                    var txResults = new LinkedHashMap<String, Object>();
                    for (int i = 0; i < transactions.size(); i++) {
                        Transaction tx = transactions.get(i);
                        var entry = accepted.get(i);
                        var result = new LinkedHashMap<String, Object>();
                        result.put("txid", tx.txId().toDisplayHex());
                        result.put("vsize", entry.virtualSize());
                        result.put("vsize_adjusted", entry.virtualSize());
                        result.put("vsize_bip141", ru.bitcoin.node.consensus.transaction.TransactionWeight.virtualSize(entry.weight()));
                        result.put("fees", Map.of("base", satoshisToBtc(entry.fee())));
                        txResults.put(tx.wtxId().toDisplayHex(), result);
                    }
                    var result = new LinkedHashMap<String, Object>();
                    result.put("package_msg", "success");
                    result.put("tx-results", txResults);
                    result.put("replaced-transactions", submission.replacedTransactions().stream()
                            .map(Hash256::toDisplayHex).toList());
                    yield result;
                } catch (ru.bitcoin.node.mempool.MempoolAdmissionException
                         | ru.bitcoin.node.consensus.transaction.TransactionValidationException
                         | ru.bitcoin.node.script.ScriptExecutionException
                         | ru.bitcoin.node.script.ScriptParseException exception) {
                    throw new RpcException(-26, exception.getMessage());
                }
            }
            case "decoderawtransaction" -> {
                if (params.size() != 1 || !(params.getFirst() instanceof String raw))
                    throw new RpcException(-32602, "Expected one raw transaction hex string");
                try {
                    Transaction transaction = TransactionParser.parse(HexFormat.of().parseHex(raw));
                    yield transactionJson(transaction, null, null);
                } catch (IllegalArgumentException | java.nio.BufferUnderflowException exception) {
                    throw new RpcException(-22, "TX decode failed");
                }
            }
            case "getrawtransaction" -> {
                if (params.isEmpty() || params.size() > 3 || !(params.getFirst() instanceof String txidText))
                    throw new RpcException(-32602, "Expected txid, optional verbosity and optional blockhash");
                Hash256 txid = parseHash(txidText);
                int verbosity = intOrBooleanVerbosity(params, 1, 0);
                if (verbosity < 0 || verbosity > 1)
                    throw new RpcException(-8, "Verbosity must be 0 or 1");

                Transaction transaction;
                NodeValidationService.ActiveBlockInfo blockInfo = null;

                if (params.size() >= 3 && params.get(2) != null) {
                    if (!(params.get(2) instanceof String blockHashText))
                        throw new RpcException(-32602, "blockhash must be a string");
                    Hash256 blockHash = parseHash(blockHashText);
                    blockInfo = validation.activeBlockInfo(blockHash)
                            .orElseThrow(() -> new RpcException(-5, "Block hash not found"));
                    if (validation.findBlock(blockHash).isEmpty()) {
                        if (validation.isPrunedActiveBlock(blockHash))
                            throw new RpcException(-1, "Block not available (pruned data)");
                        throw new RpcException(-1, "Block not available");
                    }
                    transaction = validation.transactionInActiveBlock(txid, blockHash)
                            .orElseThrow(() -> new RpcException(-5, "No such transaction found in the provided block"));
                } else {
                    var mempool = validation.mempoolEntry(txid);
                    if (mempool.isPresent()) {
                        transaction = mempool.orElseThrow().transaction();
                    } else {
                        var indexed = validation.indexedTransaction(txid);
                        if (indexed.isEmpty()) {
                            String message = validation.txIndexEnabled()
                                    ? "No such mempool or blockchain transaction"
                                    : "No such mempool transaction. Use -txindex or provide a block hash to query blockchain transactions";
                            throw new RpcException(-5, message);
                        }
                        transaction = indexed.orElseThrow().transaction();
                        blockInfo = indexed.orElseThrow().blockInfo();
                    }
                }

                if (verbosity == 0)
                    yield HexFormat.of().formatHex(TransactionSerializer.serialize(transaction));
                yield transactionJson(transaction, blockInfo, null);
            }
            case "prioritisetransaction" -> {
                if (params.size() != 3 || !(params.getFirst() instanceof String txidText))
                    throw new RpcException(-32602, "Expected txid, dummy and fee_delta");
                if (params.get(1) != null && (!(params.get(1) instanceof Number n) || n.longValue() != 0L))
                    throw new RpcException(-8, "Priority is no longer supported, dummy argument must be 0 or null");
                if (!(params.get(2) instanceof Number deltaNumber)) throw new RpcException(-32602, "fee_delta must be numeric");
                long delta = deltaNumber.longValue();
                try { validation.prioritiseTransaction(parseHash(txidText), delta); }
                catch (ru.bitcoin.node.mempool.MempoolAdmissionException e) { throw new RpcException(-8, e.getMessage()); }
                yield true;
            }
            case "getprioritisedtransactions" -> {
                requireNoParams(method, params);
                var result = new LinkedHashMap<String, Object>();
                for (var e : validation.prioritisedTransactions().entrySet()) {
                    var item = new LinkedHashMap<String, Object>();
                    item.put("fee_delta", e.getValue());
                    var mempoolView = validation.mempoolGraphEntry(e.getKey());
                    item.put("in_mempool", mempoolView.isPresent());
                    mempoolView.ifPresent(view -> item.put("modified_fee", view.entry().modifiedFee()));
                    result.put(e.getKey().toDisplayHex(), item);
                }
                yield result;
            }
            case "getmempoolentry" -> {
                if (params.size() != 1 || !(params.getFirst() instanceof String txidText))
                    throw new RpcException(-32602, "Expected one transaction id");
                Hash256 txid = parseHash(txidText);
                var view = validation.mempoolGraphEntry(txid)
                        .orElseThrow(() -> new RpcException(-5, "Transaction not in mempool"));
                yield mempoolEntryJson(view);
            }
            case "getmempoolancestors" -> {
                if (params.isEmpty() || params.size() > 2 || !(params.getFirst() instanceof String txidText))
                    throw new RpcException(-32602, "Expected txid and optional verbose boolean");
                boolean verbose = booleanParam(params, 1, false);
                Hash256 txid = parseHash(txidText);
                var query = validation.mempoolAncestorQuery(txid)
                        .orElseThrow(() -> new RpcException(-5, "Transaction not in mempool"));
                if (!verbose) yield query.txIds().stream().map(Hash256::toDisplayHex).toList();
                var result = new LinkedHashMap<String, Object>();
                for (Hash256 id : query.txIds()) {
                    var view = query.graphViews().get(id);
                    if (view != null) result.put(id.toDisplayHex(), mempoolEntryJson(view));
                }
                yield result;
            }
            case "getmempooldescendants" -> {
                if (params.isEmpty() || params.size() > 2 || !(params.getFirst() instanceof String txidText))
                    throw new RpcException(-32602, "Expected txid and optional verbose boolean");
                boolean verbose = booleanParam(params, 1, false);
                Hash256 txid = parseHash(txidText);
                var query = validation.mempoolDescendantQuery(txid)
                        .orElseThrow(() -> new RpcException(-5, "Transaction not in mempool"));
                if (!verbose) yield query.txIds().stream().map(Hash256::toDisplayHex).toList();
                var result = new LinkedHashMap<String, Object>();
                for (Hash256 id : query.txIds()) {
                    var view = query.graphViews().get(id);
                    if (view != null) result.put(id.toDisplayHex(), mempoolEntryJson(view));
                }
                yield result;
            }
            case "getmempoolcluster" -> {
                if (params.size() != 1 || !(params.getFirst() instanceof String txidText))
                    throw new RpcException(-32602, "Expected one transaction id");
                Hash256 txid = parseHash(txidText);
                var cluster = validation.mempoolCluster(txid)
                        .orElseThrow(() -> new RpcException(-5, "Transaction not in mempool"));
                var result = new LinkedHashMap<String, Object>();
                result.put("clusterweight", cluster.adjustedWeight());
                result.put("txcount", cluster.transactionCount());
                result.put("chunks", cluster.chunks().stream().map(chunk -> Map.of(
                        "chunkfee", satoshisToBtc(chunk.fee()),
                        "chunkweight", chunk.adjustedWeight(),
                        "txs", chunk.transactions().stream().map(Hash256::toDisplayHex).toList()
                )).toList());
                yield result;
            }
            case "getmempoolfeeratediagram" -> {
                if (!params.isEmpty()) throw new RpcException(-32602, "getmempoolfeeratediagram takes no parameters");
                long weight = 0, fee = 0;
                List<Map<String, Object>> result = new ArrayList<>();
                for (var chunk : validation.mempoolFeeRateDiagram()) {
                    weight = Math.addExact(weight, chunk.adjustedWeight());
                    fee = Math.addExact(fee, chunk.fee());
                    result.add(Map.of("weight", weight, "fee", satoshisToBtc(fee)));
                }
                yield result;
            }
            case "gettxspendingprevout" -> {
                if (params.isEmpty() || params.size() > 2 || !(params.getFirst() instanceof List<?> outputs))
                    throw new RpcException(-32602, "Expected outputs array and optional options object");
                if (outputs.isEmpty()) throw new RpcException(-8, "Invalid parameter, outputs are missing");
                Map<?, ?> options = Map.of();
                if (params.size() == 2) {
                    if (!(params.get(1) instanceof Map<?, ?> map)) throw new RpcException(-32602, "Expected options object");
                    for (Object key : map.keySet()) {
                        if (!(key instanceof String text) || (!text.equals("mempool_only") && !text.equals("return_spending_tx")))
                            throw new RpcException(-32602, "Unknown named parameter");
                    }
                    options = map;
                }
                boolean mempoolOnly = !validation.txOutSpenderIndexEnabled();
                if (options.containsKey("mempool_only")) {
                    if (!(options.get("mempool_only") instanceof Boolean value)) throw new RpcException(-32602, "mempool_only must be boolean");
                    mempoolOnly = value;
                }
                boolean returnSpendingTx = false;
                if (options.containsKey("return_spending_tx")) {
                    if (!(options.get("return_spending_tx") instanceof Boolean value)) throw new RpcException(-32602, "return_spending_tx must be boolean");
                    returnSpendingTx = value;
                }
                List<Map<String, Object>> result = new ArrayList<>(outputs.size());
                for (Object raw : outputs) {
                    if (!(raw instanceof Map<?, ?> output) || output.size() != 2
                            || !(output.get("txid") instanceof String txidText)
                            || !(output.get("vout") instanceof Number voutNumber))
                        throw new RpcException(-32602, "Each output must contain txid and vout");
                    long vout = voutNumber.longValue();
                    if (vout < 0) throw new RpcException(-8, "Invalid parameter, vout cannot be negative");
                    if (vout > 0xffff_ffffL) throw new RpcException(-8, "Invalid parameter, vout is out of range");
                    Hash256 txid = parseHash(txidText);
                    OutPoint outPoint = new OutPoint(txid, new UInt32(vout));
                    NodeValidationService.SpendingTransaction spender;
                    try {
                        spender = validation.spendingTransaction(outPoint, mempoolOnly).orElse(null);
                    } catch (IllegalStateException exception) {
                        throw new RpcException(-1, exception.getMessage());
                    }
                    var rendered = new LinkedHashMap<String, Object>();
                    rendered.put("txid", txid.toDisplayHex());
                    rendered.put("vout", vout);
                    if (spender != null) {
                        rendered.put("spendingtxid", spender.transaction().txId().toDisplayHex());
                        if (returnSpendingTx) rendered.put("spendingtx",
                                HexFormat.of().formatHex(TransactionSerializer.serialize(spender.transaction())));
                        if (spender.confirmed()) rendered.put("blockhash", spender.blockHash().toDisplayHex());
                    }
                    result.add(rendered);
                }
                yield result;
            }
            case "gettxout" -> {
                if (params.size() < 2 || params.size() > 3 || !(params.getFirst() instanceof String txidText)
                        || !(params.get(1) instanceof Number voutNumber))
                    throw new RpcException(-32602, "Expected txid, vout and optional include_mempool");
                long vout = voutNumber.longValue();
                boolean includeMempool = booleanParam(params, 2, true);
                var output = validation.txOut(parseHash(txidText), vout, includeMempool);
                if (output.isEmpty()) yield null;
                var coin = output.get();
                var result = new LinkedHashMap<String, Object>();
                result.put("bestblock", validation.activeTip().hash().toDisplayHex());
                result.put("confirmations", coin.confirmations());
                result.put("value", satoshisToBtc(coin.amount()));
                result.put("scriptPubKey", Map.of("hex", HexFormat.of().formatHex(coin.scriptPubKey())));
                result.put("coinbase", coin.coinbase());
                yield result;
            }
            case "gettxoutsetinfo" -> {
                if (params.size() > 3 || (!params.isEmpty() && !(params.getFirst() instanceof String)))
                    throw new RpcException(-32602, "Expected optional hash_type, hash_or_height and use_index");
                String hashType = params.isEmpty() ? "hash_serialized_3" : (String) params.getFirst();
                if (!hashType.equals("hash_serialized_3") && !hashType.equals("muhash") && !hashType.equals("none"))
                    throw new RpcException(-8, "'" + hashType + "' is not a supported hash_type");
                Object hashOrHeight = params.size() > 1 ? params.get(1) : null;
                boolean useIndex = booleanParam(params, 2, true);

                NodeValidationService.UtxoSetInfo stats;
                boolean usedIndex = false;
                if (hashOrHeight != null) {
                    if (!(hashOrHeight instanceof String) && !(hashOrHeight instanceof Number))
                        throw new RpcException(-32602, "hash_or_height must be a block hash or height");
                    if (hashType.equals("hash_serialized_3"))
                        throw new RpcException(-8, "hash_serialized_3 cannot be queried for a specific block");
                    if (!useIndex || !validation.coinStatsIndexEnabled())
                        throw new RpcException(-8, "Querying specific block heights requires coinstatsindex");
                    usedIndex = true;
                    if (hashOrHeight instanceof Number number) {
                        long height = number.longValue();
                        stats = validation.indexedUtxoSetInfo(height)
                                .orElseThrow(() -> new RpcException(-8, "Block height out of range"));
                    } else {
                        Hash256 hash = parseHash((String) hashOrHeight);
                        var active = validation.activeBlockInfo(hash)
                                .orElseThrow(() -> new RpcException(-5, "Block not found"));
                        stats = validation.indexedUtxoSetInfo(active.index().hash())
                                .orElseThrow(() -> new RpcException(-32603, "Coinstats index is not synchronized to the requested block"));
                    }
                } else if (useIndex && validation.coinStatsIndexEnabled() && !hashType.equals("hash_serialized_3")) {
                    usedIndex = true;
                    stats = validation.indexedUtxoSetInfo(validation.activeTip().hash()).orElseThrow(() ->
                            new RpcException(-32603, "Coinstats index is not synchronized to the active tip"));
                } else {
                    stats = validation.utxoSetInfo(switch (hashType) {
                        case "hash_serialized_3" -> ru.bitcoin.node.storage.utxo.RocksDbUtxoStore.HashType.HASH_SERIALIZED_3;
                        case "muhash" -> ru.bitcoin.node.storage.utxo.RocksDbUtxoStore.HashType.MUHASH;
                        case "none" -> ru.bitcoin.node.storage.utxo.RocksDbUtxoStore.HashType.NONE;
                        default -> throw new IllegalStateException("unreachable hash type");
                    });
                }
                var result = new LinkedHashMap<String, Object>();
                result.put("height", stats.height());
                result.put("bestblock", stats.bestBlock().toDisplayHex());
                if (!usedIndex) result.put("transactions", stats.transactions());
                result.put("txouts", stats.txouts());
                result.put("bogosize", stats.bogoSize());
                if (hashType.equals("hash_serialized_3"))
                    result.put("hash_serialized_3", stats.hashSerialized3().toDisplayHex());
                if (hashType.equals("muhash"))
                    result.put("muhash", stats.muhash().toDisplayHex());
                if (!usedIndex) result.put("disk_size", stats.diskSize());
                result.put("total_amount", satoshisToBtc(stats.totalAmount()));
                yield result;
            }
            case "getblockhash" -> {
                long height = longParam(params, 0, "height");
                yield validation.activeBlockInfo(height)
                        .orElseThrow(() -> new RpcException(-8, "Block height out of range"))
                        .index().hash().toDisplayHex();
            }
            case "getblockheader" -> {
                if (params.isEmpty() || params.size() > 2 || !(params.getFirst() instanceof String hashText))
                    throw new RpcException(-32602, "Expected block hash and optional verbosity");
                boolean verbose = booleanParam(params, 1, true);
                Hash256 hash = parseHash(hashText);
                var info = validation.activeBlockInfo(hash)
                        .orElseThrow(() -> new RpcException(-5, "Block not found"));
                if (!verbose) {
                    yield HexFormat.of().formatHex(BlockHeaderSerializer.serialize(info.index().header()));
                }
                yield blockHeaderJson(info);
            }
            case "getblock" -> {
                if (params.isEmpty() || params.size() > 2 || !(params.getFirst() instanceof String hashText))
                    throw new RpcException(-32602, "Expected block hash and optional verbosity");
                int verbosity = intParam(params, 1, 1);
                if (verbosity < 0 || verbosity > 1)
                    throw new RpcException(-8, "Verbosity must be 0 or 1");
                Hash256 hash = parseHash(hashText);
                var info = validation.activeBlockInfo(hash)
                        .orElseThrow(() -> new RpcException(-5, "Block not found"));
                var block = validation.findBlock(hash);
                if (block.isEmpty()) {
                    if (validation.isPrunedActiveBlock(hash))
                        throw new RpcException(-1, "Block not available (pruned data)");
                    throw new RpcException(-1, "Block not available");
                }
                if (verbosity == 0) {
                    yield HexFormat.of().formatHex(BlockSerializer.serialize(block.get()));
                }
                var result = new LinkedHashMap<String, Object>(blockHeaderJson(info));
                result.put("size", BlockSerializer.serialize(block.get()).length);
                result.put("weight", blockWeight(block.get()));
                result.put("nTx", block.get().transactions().size());
                result.put("tx", block.get().transactions().stream()
                        .map(tx -> tx.txId().toDisplayHex()).toList());
                yield result;
            }
            case "getconnectioncount" -> requirePeerManager().size();
            case "getpeerinfo" -> requirePeerManager().managedPeers().stream().map(managed -> {
                var peer = managed.peer();
                var info = new LinkedHashMap<String, Object>();
                var remote = peer.remoteAddress();
                info.put("addr", remote == null ? "" : remote.getHostString() + ":" + remote.getPort());
                info.put("inbound", peer.isInboundConnection());
                info.put("connection_type", managed.role().name().toLowerCase(Locale.ROOT));
                info.put("transport_protocol_type", peer.isV2Transport() ? "v2" : "v1");
                byte[] sessionId = peer.transportSessionId();
                info.put("session_id", sessionId == null ? "" : HexFormat.of().formatHex(sessionId));
                info.put("v2_fallback", peer.usedV2Fallback());
                info.put("state", peer.state().name().toLowerCase(Locale.ROOT));
                peer.lastPingRoundTrip().ifPresent(v -> info.put("pingtime", v.toNanos() / 1_000_000_000.0));
                peer.minPingRoundTrip().ifPresent(v -> info.put("minping", v.toNanos() / 1_000_000_000.0));
                if (peer.isReady()) {
                    var version = peer.remoteVersion();
                    info.put("version", version.version());
                    info.put("subver", version.userAgent());
                    info.put("services", String.format("%016x", version.services()));
                    info.put("startingheight", version.startHeight());
                }
                return info;
            }).toList();
            case "disconnectnode" -> {
                String host = stringParam(params);
                try { requirePeerManager().disconnect(java.net.InetAddress.getByName(host)); }
                catch (java.net.UnknownHostException e) { throw new RpcException(-8, "Invalid address"); }
                yield null;
            }
            case "setban" -> {
                if (params.size() < 2 || params.size() > 4 || !(params.get(0) instanceof String subnet) || !(params.get(1) instanceof String command))
                    throw new RpcException(-32602, "Expected subnet, command, optional bantime and absolute");
                if (command.equals("add")) {
                    long banTime = params.size() > 2 && params.get(2) != null ? ((Number) params.get(2)).longValue() : 0L;
                    boolean absolute = params.size() > 3 && params.get(3) != null && (Boolean) params.get(3);
                    requirePeerManager().banManager().ban(subnet, banTime, absolute);
                    for (var peer : requirePeerManager().peers()) {
                        var remote = peer.remoteAddress();
                        if (remote != null && remote.getAddress() != null && requirePeerManager().banManager().isBanned(remote.getAddress()))
                            try { peer.close(); } catch (IOException ignored) { }
                    }
                } else if (command.equals("remove")) {
                    if (!requirePeerManager().banManager().unban(subnet)) throw new RpcException(-30, "Unban failed");
                } else throw new RpcException(-8, "Command must be add or remove");
                yield null;
            }
            case "listbanned" -> requirePeerManager().banManager().entries().stream().map(entry -> Map.of(
                    "address", entry.subnet(), "ban_created", entry.banCreated(), "banned_until", entry.bannedUntil())).toList();
            case "clearbanned" -> { requirePeerManager().banManager().clear(); yield null; }
            case "pruneblockchain" -> {
                long height = longParam(params, 0, "height");
                if (height < 0) throw new RpcException(-8, "Negative block height");
                if (!validation.pruneInfo().enabled())
                    throw new RpcException(-1, "Cannot prune blocks because node is not in prune mode");
                try {
                    yield validation.pruneToHeight(height);
                } catch (IllegalArgumentException exception) {
                    throw new RpcException(-8, exception.getMessage());
                } catch (IllegalStateException exception) {
                    throw new RpcException(-1, exception.getMessage());
                }
            }
            case "invalidateblock" -> {
                if (params.size() != 1 || !(params.get(0) instanceof String text))
                    throw new RpcException(-32602, "Expected block hash");
                try {
                    validation.invalidateBlock(parseHash(text));
                } catch (IllegalArgumentException exception) {
                    throw new RpcException(-5, exception.getMessage());
                } catch (IllegalStateException exception) {
                    throw new RpcException(-1, exception.getMessage());
                }
                yield null;
            }
            case "reconsiderblock" -> {
                if (params.size() != 1 || !(params.get(0) instanceof String text))
                    throw new RpcException(-32602, "Expected block hash");
                try {
                    validation.reconsiderBlock(parseHash(text));
                } catch (IllegalArgumentException exception) {
                    throw new RpcException(-5, exception.getMessage());
                } catch (IllegalStateException exception) {
                    throw new RpcException(-1, exception.getMessage());
                }
                yield null;
            }
            case "getchaintips" -> {
                if (!params.isEmpty()) throw new RpcException(-32602, "getchaintips takes no parameters");
                yield validation.chainTips().stream().map(tip -> {
                    var value = new LinkedHashMap<String, Object>();
                    value.put("height", tip.height());
                    value.put("hash", tip.hash().toDisplayHex());
                    value.put("branchlen", tip.branchLength());
                    value.put("status", tip.status());
                    return value;
                }).toList();
            }
            case "getblockfilter" -> {
                if (params.isEmpty() || params.size() > 2 || !(params.getFirst() instanceof String hashText))
                    throw new RpcException(-32602, "Expected block hash and optional filter type");
                String filterType = params.size() == 2 ? String.valueOf(params.get(1)) : "basic";
                if (!"basic".equals(filterType)) throw new RpcException(-5, "Unknown filtertype " + filterType);
                if (!validation.blockFilterIndexEnabled())
                    throw new RpcException(-1, "Index is not enabled for filtertype basic");
                Hash256 hash = parseHash(hashText);
                if (validation.activeBlockInfo(hash).isEmpty()) throw new RpcException(-5, "Block not found");
                var filter = validation.blockFilter(hash).orElseThrow(() ->
                        new RpcException(-1, "Block filter index is not synchronized to the requested block"));
                var result = new LinkedHashMap<String, Object>();
                result.put("filter", HexFormat.of().formatHex(filter.filter()));
                result.put("header", filter.header().toDisplayHex());
                yield result;
            }
            case "dumptxoutset" -> {
                if (params.isEmpty() || params.size() > 2 || !(params.getFirst() instanceof String pathText))
                    throw new RpcException(-32602, "Expected path and optional type");
                if (params.size() == 2 && params.get(1) != null && !(params.get(1) instanceof String))
                    throw new RpcException(-32602, "Snapshot type must be a string");
                String type = params.size() == 2 && params.get(1) != null ? (String) params.get(1) : "latest";
                if (!type.equals("latest")) throw new RpcException(-8, "Only latest snapshots are supported");
                try {
                    var dump = validation.dumpUtxoSnapshot(java.nio.file.Path.of(pathText));
                    var result = new LinkedHashMap<String,Object>();
                    result.put("coins_written", dump.coinsWritten());
                    result.put("base_hash", dump.baseHash().toDisplayHex());
                    result.put("base_height", dump.baseHeight());
                    result.put("path", dump.path().toString());
                    yield result;
                } catch (java.nio.file.FileAlreadyExistsException exception) {
                    throw new RpcException(-8, "Snapshot file already exists");
                } catch (IOException exception) {
                    throw new RpcException(-1, "Unable to write UTXO snapshot: " + exception.getMessage());
                }
            }
            case "loadtxoutset" -> {
                if (params.size() != 1 || !(params.getFirst() instanceof String pathText))
                    throw new RpcException(-32602, "Expected snapshot path");
                try {
                    var loaded = validation.loadUtxoSnapshot(java.nio.file.Path.of(pathText));
                    var result = new LinkedHashMap<String,Object>();
                    result.put("coins_loaded", loaded.coinsLoaded());
                    result.put("base_hash", loaded.baseHash().toDisplayHex());
                    result.put("base_height", loaded.baseHeight());
                    result.put("hash_serialized_3", loaded.hashSerialized().toDisplayHex());
                    result.put("chain_tx_count", loaded.chainTxCount());
                    yield result;
                } catch (IOException exception) {
                    throw new RpcException(-1, "Unable to load UTXO snapshot: " + exception.getMessage());
                }
            }
            case "getchainstates" -> {
                if (!params.isEmpty()) throw new RpcException(-32602, "getchainstates takes no parameters");
                var result = new LinkedHashMap<String,Object>();
                result.put("headers", sync.headerChainState().bestHeaderTip().height());
                result.put("chainstates", validation.chainStates().stream().map(state -> {
                    var item = new LinkedHashMap<String,Object>();
                    item.put("blocks", state.blocks());
                    item.put("bestblockhash", state.bestBlockHash().toDisplayHex());
                    if (state.snapshot()) item.put("snapshot_blockhash", state.bestBlockHash().toDisplayHex());
                    item.put("validated", state.validated());
                    return item;
                }).toList());
                yield result;
            }
            case "getblockchaininfo" -> {
                var tip = validation.downloadTip();
                var prune = validation.downloadPruningEnabled() ? validation.pruneInfo() : null;
                var info = new LinkedHashMap<String, Object>();
                info.put("chain", chainName());
                info.put("blocks", tip.height());
                info.put("headers", sync.headerChainState().publishedBestHeaderTip().height());
                info.put("bestblockhash", tip.hash().toDisplayHex());
                info.put("chainwork", String.format("%064x", tip.chainWork()));
                info.put("difficulty", difficulty(tip));
                info.put("initialblockdownload", validation.downloadInitialBlockDownload());
                info.put("pruned", prune != null && prune.enabled());
                if (prune != null && prune.enabled()) {
                    info.put("pruneheight", prune.pruneHeight());
                    info.put("automatic_pruning", prune.automatic());
                    if (prune.automatic()) info.put("prune_target_size", prune.targetBytes());
                }
                yield info;
            }
            case "getblockcount" -> {
                requireNoParams(method, params);
                yield validation.activeTip().height();
            }
            case "getbestblockhash" -> {
                requireNoParams(method, params);
                yield validation.activeTip().hash().toDisplayHex();
            }
            case "getdifficulty" -> {
                requireNoParams(method, params);
                yield difficulty();
            }
            case "getnetworkhashps" -> {
                if (params.size() > 2) throw new RpcException(-32602, "getnetworkhashps takes at most two parameters");
                int lookup = params.isEmpty() ? 120 : numberParam(params, 0).intValue();
                long height = params.size() < 2 ? -1L : numberParam(params, 1).longValue();
                yield networkHashPs(lookup, height);
            }
            case "uptime" -> {
                requireNoParams(method, params);
                yield TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - startedNanos);
            }
            case "getmempoolinfo" -> {
                requireNoParams(method, params);
                var entries = validation.mempoolEntries();
                long bytes = entries.stream().mapToLong(e -> e.virtualSize()).sum();
                var info = new LinkedHashMap<String, Object>();
                info.put("loaded", true);
                info.put("size", entries.size());
                info.put("bytes", bytes);
                info.put("usage", bytes);
                info.put("total_fee", satoshisToBtc(entries.stream().mapToLong(ru.bitcoin.node.mempool.MempoolEntry::fee).sum()));
                info.put("maxmempool", MempoolLimits.DEFAULT.maxPoolVirtualBytes());
                info.put("mempoolminfee", satoshisPerKvBToBtcPerKvB(validation.feeFilterRate()));
                info.put("minrelaytxfee", satoshisPerKvBToBtcPerKvB(validation.minimumRelayFeeRate()));
                info.put("incrementalrelayfee", satoshisPerKvBToBtcPerKvB(MempoolLimits.DEFAULT.incrementalRelaySatPerKvB()));
                info.put("unbroadcastcount", validation.unbroadcastMempoolCount());
                info.put("limitclustercount", MempoolLimits.DEFAULT.clusterCount());
                info.put("limitclustersize", MempoolLimits.DEFAULT.clusterVirtualBytes());
                info.put("optimal", true);
                info.put("fullrbf", true);
                yield info;
            }
            case "getnetworkinfo" -> {
                requireNoParams(method, params);
                long services = localServices;
                var info = new LinkedHashMap<String, Object>();
                info.put("version", 1);
                info.put("subversion", "/java-bitcoin-node:0.0.1/");
                info.put("protocolversion", VersionMessage.CURRENT_PROTOCOL_VERSION);
                info.put("localservices", String.format("%016x", services));
                info.put("localservicesnames", serviceNames(services));
                info.put("localrelay", true);
                info.put("timeoffset", 0);
                info.put("networkactive", true);
                info.put("connections", peerManager == null ? 0 : peerManager.size());
                info.put("connections_in", peerManager == null ? 0 : peerManager.peers().stream().filter(ru.bitcoin.node.p2p.Peer::isInboundConnection).count());
                info.put("connections_out", peerManager == null ? 0 : peerManager.peers().stream().filter(p -> !p.isInboundConnection()).count());
                info.put("connections_v1", peerManager == null ? 0 : peerManager.peers().stream().filter(p -> !p.isV2Transport()).count());
                info.put("connections_v2", peerManager == null ? 0 : peerManager.peers().stream().filter(ru.bitcoin.node.p2p.Peer::isV2Transport).count());
                info.put("relayfee", satoshisPerKvBToBtcPerKvB(validation.minimumRelayFeeRate()));
                info.put("incrementalfee", satoshisPerKvBToBtcPerKvB(MempoolLimits.DEFAULT.incrementalRelaySatPerKvB()));
                info.put("warnings", "");
                yield info;
            }
            case "getindexinfo" -> {
                if (params.size() > 1 || (!params.isEmpty() && !(params.getFirst() instanceof String)))
                    throw new RpcException(-32602, "Expected optional index name");
                String requested = params.isEmpty() ? null : (String) params.getFirst();
                var indexes = new LinkedHashMap<String, Object>();
                addIndexInfo(indexes, requested, "txindex", validation.txIndexEnabled(), validation.activeTip().height());
                addIndexInfo(indexes, requested, "txospenderindex", validation.txOutSpenderIndexEnabled(), validation.activeTip().height());
                addIndexInfo(indexes, requested, "coinstatsindex", validation.coinStatsIndexEnabled(), validation.activeTip().height());
                addIndexInfo(indexes, requested, "basic block filter index", validation.blockFilterIndexEnabled(), validation.activeTip().height());
                yield indexes;
            }
            case "getmininginfo" ->
                    Map.of("blocks", validation.activeTip().height(), "pooledtx", validation.mempoolEntries().size(),
                            "miningready", ready.getAsBoolean());
            case "getrawmempool" -> {
                if (params.size() > 2) throw new RpcException(-32602, "getrawmempool takes at most two parameters");
                boolean verbose = booleanParam(params, 0, false);
                boolean includeSequence = booleanParam(params, 1, false);
                if (verbose && includeSequence)
                    throw new RpcException(-8, "Verbose results cannot contain mempool sequence");
                if (verbose) {
                    var snapshot = validation.detailedMempoolSnapshot();
                    var result = new LinkedHashMap<String, Object>();
                    for (var entry : snapshot.entries()) {
                        Hash256 id = entry.transaction().txId();
                        var view = snapshot.graphViews().get(id);
                        if (view != null) result.put(id.toDisplayHex(), mempoolEntryJson(view));
                    }
                    yield result;
                }
                var snapshot = validation.mempoolSnapshot();
                List<String> txids = snapshot.entries().stream()
                        .map(entry -> entry.transaction().txId().toDisplayHex()).toList();
                if (!includeSequence) yield txids;
                yield Map.of("txids", txids, "mempool_sequence", snapshot.sequence());
            }
            default -> throw new RpcException(-32601, "Method not found");
        };
    }

    private Map<String, Object> mempoolEntryJson(ru.bitcoin.node.mempool.Mempool.EntryGraphView view) {
        var entry = view.entry();
        var result = new LinkedHashMap<String, Object>();
        result.put("vsize", entry.virtualSize());
        result.put("vsize_adjusted", entry.virtualSize());
        result.put("vsize_bip141", TransactionWeight.virtualSize(entry.weight()));
        result.put("weight", entry.weight());
        result.put("time", entry.arrivalTime());
        result.put("height", entry.admissionHeight());
        result.put("ancestorcount", view.ancestors().size());
        result.put("ancestorsize", view.ancestorVirtualSize());
        result.put("descendantcount", view.descendants().size());
        result.put("descendantsize", view.descendantVirtualSize());
        result.put("chunkweight", view.chunk().adjustedWeight());
        result.put("wtxid", entry.transaction().wtxId().toDisplayHex());
        result.put("fees", Map.of(
                "base", satoshisToBtc(entry.fee()),
                "modified", satoshisToBtc(entry.modifiedFee()),
                "ancestor", satoshisToBtc(view.ancestorFee()),
                "descendant", satoshisToBtc(view.descendantFee()),
                "chunk", satoshisToBtc(view.chunk().fee())));
        result.put("depends", view.parents().stream().map(Hash256::toDisplayHex).sorted().toList());
        result.put("spentby", view.children().stream().map(Hash256::toDisplayHex).sorted().toList());
        result.put("unbroadcast", validation.isMempoolUnbroadcast(entry.transaction().txId()));
        return result;
    }

    private static void requireNoParams(String method, List<?> params) {
        if (!params.isEmpty()) throw new RpcException(-32602, method + " takes no parameters");
    }

    private static Number numberParam(List<?> params, int index) {
        Object value = params.get(index);
        if (!(value instanceof Number number)) throw new RpcException(-32602, "Expected numeric parameter");
        return number;
    }

    private String chainName() {
        return switch (validation.networkParameters().network()) {
            case MAINNET -> "main";
            case TESTNET -> "test";
            case TESTNET4 -> "testnet4";
            case SIGNET -> "signet";
            case REGTEST -> "regtest";
        };
    }

    private double difficulty() { return difficulty(validation.activeTip()); }

    private double difficulty(ru.bitcoin.node.chain.BlockIndex tip) {
        var target = ru.bitcoin.node.consensus.pow.CompactTarget.decode(tip.header().bits().value());
        if (target.signum() <= 0) return 0.0d;
        var difficultyOne = ru.bitcoin.node.consensus.pow.CompactTarget.decode(0x1d00ffffL);
        return new java.math.BigDecimal(difficultyOne)
                .divide(new java.math.BigDecimal(target), java.math.MathContext.DECIMAL64)
                .doubleValue();
    }

    private double networkHashPs(int lookup, long requestedHeight) {
        var tip = validation.activeTip();
        if (lookup < -1 || lookup == 0)
            throw new RpcException(-8, "Invalid nblocks. Must be a positive number or -1.");
        if (requestedHeight < -1 || requestedHeight > tip.height())
            throw new RpcException(-8, "Block does not exist at specified height");
        long height = requestedHeight < 0 ? tip.height() : requestedHeight;
        var end = validation.activeBlockInfo(height).orElseThrow(() -> new RpcException(-8, "Block height out of range")).index();
        if (height == 0) return 0.0d;
        int blocks = lookup == -1
                ? Math.toIntExact(height % validation.networkParameters().difficultyAdjustmentInterval() + 1)
                : lookup;
        blocks = Math.min(blocks, Math.toIntExact(height));
        long startHeight = height - blocks;
        var start = validation.activeBlockInfo(startHeight).orElseThrow().index();
        long minTime = end.header().timestamp().value();
        long maxTime = minTime;
        for (long h = startHeight; h <= height; h++) {
            long timestamp = validation.activeBlockInfo(h).orElseThrow().index().header().timestamp().value();
            minTime = Math.min(minTime, timestamp);
            maxTime = Math.max(maxTime, timestamp);
        }
        if (maxTime == minTime) return 0.0d;
        java.math.BigInteger work = end.chainWork().subtract(start.chainWork());
        return new java.math.BigDecimal(work)
                .divide(java.math.BigDecimal.valueOf(maxTime - minTime), java.math.MathContext.DECIMAL64)
                .doubleValue();
    }

    private static java.math.BigDecimal satoshisPerKvBToBtcPerKvB(long value) {
        return java.math.BigDecimal.valueOf(value, 8);
    }

    private static List<String> serviceNames(long services) {
        var result = new ArrayList<String>();
        if ((services & VersionMessage.NODE_NETWORK) != 0) result.add("NETWORK");
        if ((services & VersionMessage.NODE_WITNESS) != 0) result.add("WITNESS");
        if ((services & VersionMessage.NODE_COMPACT_FILTERS) != 0) result.add("COMPACT_FILTERS");
        if ((services & VersionMessage.NODE_NETWORK_LIMITED) != 0) result.add("NETWORK_LIMITED");
        if ((services & VersionMessage.NODE_P2P_V2) != 0) result.add("P2P_V2");
        return List.copyOf(result);
    }

    private static void addIndexInfo(Map<String, Object> target, String requested, String name, boolean enabled, long height) {
        if (!enabled || (requested != null && !requested.equals(name))) return;
        target.put(name, Map.of("synced", true, "best_block_height", height));
    }

    private int intOrBooleanVerbosity(List<?> params, int index, int defaultValue) {
        if (params.size() <= index || params.get(index) == null) return defaultValue;
        Object value = params.get(index);
        if (value instanceof Boolean bool) return bool ? 1 : 0;
        if (value instanceof Number number) return number.intValue();
        throw new RpcException(-32602, "verbosity must be numeric or boolean");
    }

    private Map<String, Object> transactionJson(
            Transaction transaction,
            NodeValidationService.ActiveBlockInfo blockInfo,
            Boolean inActiveChain
    ) {
        byte[] serialized = TransactionSerializer.serialize(transaction);
        long weight = TransactionWeight.calculate(transaction);
        var result = new LinkedHashMap<String, Object>();
        result.put("txid", transaction.txId().toDisplayHex());
        result.put("hash", transaction.wtxId().toDisplayHex());
        result.put("version", transaction.version());
        result.put("size", serialized.length);
        result.put("vsize", TransactionWeight.virtualSize(weight));
        result.put("weight", weight);
        result.put("locktime", transaction.lockTime().value());

        var vin = new ArrayList<Map<String, Object>>();
        for (var input : transaction.inputs()) {
            var item = new LinkedHashMap<String, Object>();
            if (input.previousOutput().isCoinbase()) {
                item.put("coinbase", HexFormat.of().formatHex(input.scriptSig()));
            } else {
                item.put("txid", input.previousOutput().transactionId().toDisplayHex());
                item.put("vout", input.previousOutput().outputIndex().value());
                item.put("scriptSig", Map.of("hex", HexFormat.of().formatHex(input.scriptSig())));
            }
            item.put("sequence", input.sequence().value());
            if (!input.witness().isEmpty()) {
                item.put("txinwitness", input.witness().items().stream()
                        .map(bytes -> HexFormat.of().formatHex(bytes)).toList());
            }
            vin.add(item);
        }
        result.put("vin", vin);

        var vout = new ArrayList<Map<String, Object>>();
        for (int n = 0; n < transaction.outputs().size(); n++) {
            var output = transaction.outputs().get(n);
            var item = new LinkedHashMap<String, Object>();
            item.put("value", satoshisToBtc(output.value()));
            item.put("n", n);
            item.put("scriptPubKey", Map.of("hex", HexFormat.of().formatHex(output.scriptPubKey())));
            vout.add(item);
        }
        result.put("vout", vout);
        result.put("hex", HexFormat.of().formatHex(serialized));

        if (blockInfo != null) {
            if (inActiveChain != null) result.put("in_active_chain", inActiveChain);
            result.put("blockhash", blockInfo.index().hash().toDisplayHex());
            result.put("confirmations", blockInfo.confirmations());
            result.put("time", blockInfo.index().header().timestamp().value());
            result.put("blocktime", blockInfo.index().header().timestamp().value());
        }
        return result;
    }

    private static long parseMaxFeeRate(List<?> params, int index) {
        long rate = parseMoneyParam(params, index, DEFAULT_MAX_RAW_TX_FEE_RATE, "maxfeerate");
        if (rate > MAX_RAW_TX_FEE_RATE) throw new RpcException(-3, "Fee rate (" + satoshisToBtc(rate) + ") is greater than the maximum allowed (1.00 BTC/kvB)");
        return rate;
    }

    private static long parseMoneyParam(List<?> params, int index, long defaultValue, String name) {
        if (params.size() <= index || params.get(index) == null) return defaultValue;
        Object value = params.get(index);
        final java.math.BigDecimal decimal;
        try {
            decimal = value instanceof Number || value instanceof String
                    ? new java.math.BigDecimal(value.toString()) : null;
        } catch (NumberFormatException e) {
            throw new RpcException(-3, name + " must be numeric");
        }
        if (decimal == null) throw new RpcException(-3, name + " must be numeric");
        if (decimal.signum() < 0) throw new RpcException(-3, name + " must be non-negative");
        try {
            return decimal.movePointRight(8).setScale(0, java.math.RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException e) {
            throw new RpcException(-3, "Invalid amount for " + name);
        }
    }

    private static long burnAmount(Transaction transaction) {
        long burn = 0;
        for (var output : transaction.outputs()) {
            if (UnspendableScript.isUnspendable(output.scriptPubKey())) burn = Math.addExact(burn, output.value());
        }
        return burn;
    }

    private static List<Map<String, Object>> rejectedTestMempoolAccept(List<Transaction> transactions, String reason) {
        List<Map<String, Object>> results = new ArrayList<>(transactions.size());
        for (Transaction tx : transactions) {
            var result = new LinkedHashMap<String, Object>();
            result.put("txid", tx.txId().toDisplayHex());
            result.put("wtxid", tx.wtxId().toDisplayHex());
            result.put("allowed", false);
            if (transactions.size() > 1) result.put("package-error", reason);
            else result.put("reject-reason", reason);
            results.add(result);
        }
        return results;
    }

    private static java.math.BigDecimal satoshisToBtc(long satoshis) {
        return java.math.BigDecimal.valueOf(satoshis, 8);
    }

    private Map<String, Object> blockHeaderJson(NodeValidationService.ActiveBlockInfo info) {
        var index = info.index();
        var header = index.header();
        var result = new LinkedHashMap<String, Object>();
        result.put("hash", index.hash().toDisplayHex());
        result.put("confirmations", info.confirmations());
        result.put("height", index.height());
        result.put("version", header.version());
        result.put("versionHex", String.format("%08x", header.version()));
        result.put("merkleroot", header.merkleRoot().toDisplayHex());
        result.put("time", header.timestamp().value());
        result.put("bits", String.format("%08x", header.bits().value()));
        result.put("nonce", header.nonce().value());
        result.put("chainwork", String.format("%064x", index.chainWork()));
        if (index.height() > 0) result.put("previousblockhash", index.previousBlockHash().toDisplayHex());
        if (info.nextBlockHash() != null) result.put("nextblockhash", info.nextBlockHash().toDisplayHex());
        return result;
    }

    private static int blockWeight(ru.bitcoin.node.protocol.block.Block block) {
        int total = BlockSerializer.serialize(block).length;
        int stripped = BlockSerializer.serializeLegacy(block).length;
        return Math.addExact(Math.multiplyExact(stripped, 3), total);
    }

    private static Hash256 parseHash(String text) {
        try {
            if (text.length() != 64) throw new IllegalArgumentException("hash length");
            return Hash256.fromDisplayHex(text);
        } catch (RuntimeException exception) {
            throw new RpcException(-8, "Invalid block hash");
        }
    }

    private static boolean booleanParam(List<?> params, int index, boolean defaultValue) {
        if (params.size() <= index) return defaultValue;
        Object value = params.get(index);
        if (!(value instanceof Boolean result))
            throw new RpcException(-32602, "Expected boolean parameter");
        return result;
    }

    private static int intParam(List<?> params, int index, int defaultValue) {
        if (params.size() <= index) return defaultValue;
        Object value = params.get(index);
        if (!(value instanceof Number number))
            throw new RpcException(-32602, "Expected numeric parameter");
        long result = number.longValue();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE)
            throw new RpcException(-32602, "Numeric parameter out of range");
        return (int) result;
    }

    private static long longParam(List<?> params, int index, String name) {
        if (params.size() != index + 1 || !(params.get(index) instanceof Number number))
            throw new RpcException(-32602, "Expected " + name);
        return number.longValue();
    }

    private String stringParam(List<?> params) {
        if (params.size() != 1 || !(params.getFirst() instanceof String value))
            throw new RpcException(-32602, "Expected one hexadecimal string");
        return value;
    }

    private PeerManager requirePeerManager() {
        if (peerManager == null) throw new RpcException(-32601, "Network RPC unavailable");
        return peerManager;
    }

    private String rpcHelp(String command) {
        List<String> methods = List.of(
                "clearbanned", "decoderawtransaction", "disconnectnode", "dumptxoutset", "getbestblockhash",
                "getblock", "getblockchaininfo", "getblockcount", "getblockfilter", "getblockhash", "getblockheader",
                "getblocktemplate", "getchainstates", "getchaintips", "getconnectioncount", "getdifficulty", "getindexinfo",
                "getmempoolancestors", "getmempoolcluster", "getmempooldescendants", "getmempoolentry", "getmempoolfeeratediagram",
                "getmempoolinfo", "getmininginfo", "getnetworkhashps", "getnetworkinfo", "getpeerinfo", "getprioritisedtransactions",
                "getrawmempool", "getrawtransaction", "getrpcinfo", "gettxout", "gettxoutsetinfo", "gettxspendingprevout", "help",
                "invalidateblock", "listbanned", "loadtxoutset", "ping", "prioritisetransaction", "pruneblockchain", "reconsiderblock",
                "savemempool", "sendrawtransaction", "setban", "submitblock", "submitpackage", "testmempoolaccept", "uptime");
        if (command == null) return String.join("\n", methods);
        if (!methods.contains(command)) throw new RpcException(-32601, "Method not found");
        return command + " - supported by java-bitcoin-node RPC";
    }

    private void respondNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
    }

    private void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = mapper.writeValueAsBytes(value);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        mining.close();
        server.stop(0);
        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS))
                throw new IllegalStateException("RPC workers did not stop");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
