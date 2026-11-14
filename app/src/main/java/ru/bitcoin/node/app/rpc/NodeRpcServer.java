package ru.bitcoin.node.app.rpc;

import ru.bitcoin.node.mempool.FeeRate;

import com.sun.net.httpserver.HttpExchange;
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
    private static final int MAX_REQUEST = 32 * 1024 * 1024;
    private static final long DEFAULT_MAX_RAW_TX_FEE_RATE = 10_000_000L; // 0.10 BTC/kvB
    private static final long MAX_RAW_TX_FEE_RATE = 100_000_000L; // 1 BTC/kvB
    private static final long DEFAULT_MAX_BURN_AMOUNT = 0L;
    private final CoreHttpServer server;
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(16), Thread.ofPlatform().daemon().name("bitcoin-rpc-", 0).factory());
    private final Semaphore longPolls = new Semaphore(4);
    private final JsonMapper mapper = JsonMapper.builder(tools.jackson.core.json.JsonFactory.builder()
            .streamReadConstraints(tools.jackson.core.StreamReadConstraints.builder()
                    .maxNestingDepth(512).maxStringLength(MAX_REQUEST).maxNameLength(MAX_REQUEST)
                    .maxNumberLength(MAX_REQUEST).build()).build()).build();
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
    private volatile boolean running = true;
    private final java.util.concurrent.atomic.AtomicInteger tipWaiters = new java.util.concurrent.atomic.AtomicInteger();

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
        this(address, user, password, mining, validation, relay, sync, ready, peerManager, localServices, 16);
    }

    public NodeRpcServer(InetSocketAddress address, String user, String password, MiningController mining,
                         NodeValidationService validation, NodeRelayService relay, NodeSyncInfrastructure sync,
                         BooleanSupplier ready, PeerManager peerManager, long localServices, int maximumConnections) throws IOException {
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
        server = new CoreHttpServer(address, maximumConnections, executor, this::handle);
    }

    public int port() {
        return server.getAddress().getPort();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!Set.of("POST", "GET", "PUT", "HEAD").contains(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                respondText(exchange, 405, "");
                return;
            }
            if (!exchange.getRequestURI().toString().equals("/") && !exchange.getRequestURI().toString().startsWith("/wallet/")) {
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                respondText(exchange, 404, "");
                return;
            }
            if (!exchange.getRequestMethod().equals("POST")) {
                respondText(exchange, 405, "JSONRPC server handles only POST requests");
                return;
            }
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            byte[] supplied = null;
            if (auth != null && auth.startsWith("Basic ")) {
                try { supplied = Base64.getDecoder().decode(auth.substring(6).trim()); }
                catch (IllegalArgumentException ignored) { }
            }
            byte[] expected = Base64.getDecoder().decode(new String(authorization, StandardCharsets.US_ASCII).substring(6));
            if (supplied == null || !MessageDigest.isEqual(expected, supplied)) {
                if (auth != null) java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(250));
                exchange.getResponseHeaders().set("WWW-Authenticate", "Basic realm=\"jsonrpc\"");
                respondText(exchange, 401, "");
                return;
            }
            byte[] body = exchange.getRequestBody().readNBytes(MAX_REQUEST + 1);
            if (body.length > MAX_REQUEST) {
                respondText(exchange, 413, "");
                return;
            }
            var reply = RpcProtocol.execute(body, mapper, this::executeCommand);
            if (reply.status() == 204) respondNoContent(exchange);
            else respond(exchange, reply.status(), reply.body());
        }
    }

    private void respondText(HttpExchange exchange, int status, String text) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=ISO-8859-1");
        byte[] bytes = text.getBytes(StandardCharsets.ISO_8859_1);
        if (exchange.getRequestMethod().equals("HEAD") || bytes.length == 0) exchange.sendResponseHeaders(status, -1);
        else {
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    private Object executeCommand(String method, Object rawParams) {
        boolean acquired = false;
        long started = System.nanoTime();
        try {
            List<?> params = rawParams == null ? List.of()
                    : rawParams instanceof Map<?, ?> named ? RpcMethods.positional(method, named) : (List<?>) rawParams;
            RpcMethods.validateRequest(method, params);
            if (method.equals("getblocktemplate") && !params.isEmpty() && params.getFirst() instanceof Map<?, ?> options
                    && options.containsKey("longpollid")) {
                acquired = longPolls.tryAcquire();
                if (!acquired) throw new RpcException(-8, "Too many long-poll requests");
            }
            activeCommands.put(Thread.currentThread().threadId(), new ActiveCommand(method, started));
            return dispatch(method, params);
        } catch (RpcException exception) {
            throw exception;
        } catch (IllegalArgumentException exception) {
            throw new RpcException(-32602, "Invalid parameters");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new RpcException(-32603, "Server stopping");
        } catch (RuntimeException exception) {
            org.slf4j.LoggerFactory.getLogger(NodeRpcServer.class).error("RPC failed", exception);
            throw new RpcException(-32603, "Internal error");
        } finally {
            activeCommands.remove(Thread.currentThread().threadId());
            if (acquired) longPolls.release();
        }
    }

    private record ActiveCommand(String method, long startedNanos) { }

    private Map<String, Object> estimateSmartFee(List<?> params) {
        if (params.isEmpty() || params.size() > 3 || !(params.getFirst() instanceof Number number))
            throw new RpcException(-32602, "Expected conf_target, optional estimate_mode and options");
        int target;
        try { target = new java.math.BigDecimal(number.toString()).intValueExact(); }
        catch (ArithmeticException e) { throw new RpcException(-8, "Invalid conf_target"); }
        if (target < 1 || target > 1008) throw new RpcException(-8, "Invalid conf_target, must be between 1 and 1008");
        String mode = "ECONOMICAL";
        if (params.size() > 1 && params.get(1) != null) {
            if (!(params.get(1) instanceof String text)) throw new RpcException(-3, "estimate_mode must be a string");
            mode = text.toUpperCase(Locale.ROOT);
        }
        if (!Set.of("UNSET", "ECONOMICAL", "CONSERVATIVE").contains(mode))
            throw new RpcException(-8, "Invalid estimate_mode parameter");
        Map<?, ?> options = Map.of();
        if (params.size() > 2 && params.get(2) != null) {
            if (!(params.get(2) instanceof Map<?, ?> map)) throw new RpcException(-3, "options must be an object");
            options = map;
        }
        for (var key : options.keySet())
            if (!Set.of("fee_rate_estimator", "verbosity").contains(key)) throw new RpcException(-3, "Unknown named parameter " + key);
        Object estimatorOption = options.get("fee_rate_estimator");
        if (estimatorOption != null && !(estimatorOption instanceof String)) throw new RpcException(-3, "fee_rate_estimator must be a string");
        String estimator = estimatorOption == null ? "auto" : (String) estimatorOption;
        if (!Set.of("auto", "block_policy", "mempool_policy").contains(estimator))
            throw new RpcException(-8, "Invalid fee_rate_estimator parameter, must be one of: auto, block_policy, mempool_policy");
        int verbosity = 1;
        if (options.get("verbosity") != null) {
            if (!(options.get("verbosity") instanceof Number n)) throw new RpcException(-3, "verbosity must be numeric");
            try { verbosity = new java.math.BigDecimal(n.toString()).intValueExact(); }
            catch (ArithmeticException e) { throw new RpcException(-8, "Invalid verbosity"); }
            if (verbosity < 0) throw new RpcException(-8, "Invalid verbosity");
        }
        var result = new LinkedHashMap<String, Object>();
        var estimate = validation.estimateFee(estimator, target, mode.equals("CONSERVATIVE"));
        if (estimate.error() != null) result.put("errors", List.of(estimate.error()));
        else {
            result.put("feerate", satoshisPerKvBToBtcPerKvB(estimate.satoshisPerKvB()));
            if (estimator.equals("auto")) result.put("estimator", estimate.estimator());
        }
        result.put("blocks", estimate.blocks());
        if (verbosity >= 2) {
            result.put("mempool_health_statistics", validation.mempoolFeeHealthStatistics().reversed().stream()
                    .map(block -> Map.of("block_height", block.height(), "block_weight", block.blockWeight(),
                            "mempool_txs_weight", block.mempoolWeight())).toList());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private Object dispatch(String method, List<?> params) throws InterruptedException {
        return switch (method) {
            case "estimatesmartfee" -> estimateSmartFee(params);
            case "getopenrpcinfo" -> {
                if (params.size() > 1) throw new RpcException(-32602, "Expected optional show_hidden boolean");
                yield RpcMethods.discovery(booleanParam(params, 0, false));
            }
            case "rpc.discover" -> {
                requireNoParams(method, params);
                yield RpcMethods.discovery();
            }
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
            case "waitfornewblock", "waitforblock", "waitforblockheight" -> waitForChain(method, params);
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
            case "submitheader" -> {
                String hex = (String) params.getFirst();
                final ru.bitcoin.node.protocol.block.BlockHeader header;
                try {
                    if (hex.length() < 160 || !hex.matches("(?:[0-9a-fA-F]{2})+")) throw new IllegalArgumentException();
                    header = ru.bitcoin.node.protocol.serialization.BlockHeaderParser.parse(new ru.bitcoin.node.protocol.serialization.BitcoinReader(HexFormat.of().parseHex(hex)));
                } catch (IllegalArgumentException | IndexOutOfBoundsException error) { throw new RpcException(-22, "Block header decode failed"); }
                if (validation.storedBlockIndexLookup().find(header.previousBlockHash()) == null)
                    throw new RpcException(-25, "Must submit previous header (" + header.previousBlockHash().toDisplayHex() + ") first");
                try { validation.processHeaders(sync.headerSyncService(), new ru.bitcoin.node.p2p.message.HeadersMessage(List.of(header))); }
                catch (ru.bitcoin.node.consensus.block.BlockHeaderValidationException error) { throw new RpcException(-25, error.rejectReason()); }
                yield null;
            }
            case "sendrawtransaction" -> {
                if (params.isEmpty() || params.size() > 3 || !(params.getFirst() instanceof String raw))
                    throw new RpcException(-32602, "Expected raw transaction and optional maxfeerate/maxburnamount");
                long maxBurnAmount = parseMoneyParam(params, 2, DEFAULT_MAX_BURN_AMOUNT, "maxburnamount");
                try {
                    Transaction transaction = parseRpcTransaction(raw, "TX decode failed. Make sure the tx has at least one input.");
                    for (var output : transaction.outputs())
                        if (packageBurnOutput(output.scriptPubKey()) && output.value() > maxBurnAmount)
                            throw new RpcException(-25, "Unspendable output exceeds maximum configured by user (maxburnamount)");
                    long maxFeeRate = parseMaxFeeRate(params, 1);
                    yield relay.submitTransaction(transaction, maxFeeRate).toDisplayHex();
                } catch (ru.bitcoin.node.mempool.MempoolAdmissionException exception) {
                    if (exception instanceof NodeValidationService.BroadcastRejection rejected)
                        throw new RpcException(rejected.reason() == NodeValidationService.BroadcastRejectionReason.CONFIRMED
                                ? -27 : -25, rejected.getMessage());
                    if (exception.isMissingInputs()) throw new RpcException(-25, "bad-txns-inputs-missingorspent");
                    if (exception instanceof ru.bitcoin.node.mempool.MempoolPolicyException policy) throw new RpcException(-26, policy.details());
                    throw new RpcException(-26, exception.getMessage());
                } catch (ru.bitcoin.node.consensus.transaction.TransactionValidationException
                         | ru.bitcoin.node.script.ScriptExecutionException |
                         ru.bitcoin.node.script.ScriptParseException exception) {
                    throw new RpcException(-26, exception instanceof ru.bitcoin.node.consensus.transaction.TransactionValidationException invalid
                            ? invalid.details() : exception.getMessage());
                }
            }
            case "testmempoolaccept" -> {
                if (params.isEmpty() || params.size() > 2 || !(params.getFirst() instanceof List<?> rawTransactions))
                    throw new RpcException(-32602, "Expected rawtxs array and optional maxfeerate");
                if (rawTransactions.isEmpty() || rawTransactions.size() > 25)
                    throw new RpcException(-8, "Array must contain between 1 and 25 transactions.");
                long maxFeeRate = parseMaxFeeRate(params, 1);
                List<Transaction> transactions = new ArrayList<>(rawTransactions.size());
                for (Object raw : rawTransactions) {
                    transactions.add(parsePackageTransaction(raw));
                }
                var probe = validation.testMempoolAccept(transactions);
                List<Map<String, Object>> results = new ArrayList<>(transactions.size());
                if (!probe.allowed() && probe.entries().isEmpty()) {
                    if (probe.failedTransactionIndex() >= 0) {
                        for (int i = 0; i < transactions.size(); i++) {
                            var tx = transactions.get(i);
                            var result = new LinkedHashMap<String, Object>();
                            result.put("txid", tx.txId().toDisplayHex());
                            result.put("wtxid", tx.wtxId().toDisplayHex());
                            if (i == probe.failedTransactionIndex()) {
                                result.put("allowed", false);
                                result.put("reject-reason", testAcceptRejectReason(probe.rejectReason()));
                                if (!"missing-inputs".equals(probe.rejectReason()))
                                    result.put("reject-details", probe.rejectDetails() != null ? probe.rejectDetails() : probe.rejectReason());
                            }
                            results.add(result);
                        }
                        yield results;
                    }
                    for (Transaction tx : transactions) {
                        var result = new LinkedHashMap<String, Object>();
                        result.put("txid", tx.txId().toDisplayHex());
                        result.put("wtxid", tx.wtxId().toDisplayHex());
                        if (!probe.packagePolicyFailure()) result.put("allowed", false);
                        if (transactions.size() > 1) result.put("package-error", probe.rejectReason());
                        else result.put("reject-reason", testAcceptRejectReason(probe.rejectReason()));
                        if (transactions.size() == 1 && ("txn-already-in-mempool".equals(probe.rejectReason())
                                || "txn-same-nonwitness-data-in-mempool".equals(probe.rejectReason())))
                            result.put("reject-details", probe.rejectReason());
                        if (transactions.size() == 1 && probe.rejectDetails() != null)
                            result.put("reject-details", probe.rejectDetails());
                        results.add(result);
                    }
                    yield results;
                }
                boolean maxFeeExceeded = false;
                for (int i = 0; i < transactions.size(); i++) {
                    Transaction tx = transactions.get(i);
                    var result = new LinkedHashMap<String, Object>();
                    result.put("txid", tx.txId().toDisplayHex());
                    result.put("wtxid", tx.wtxId().toDisplayHex());
                    if (maxFeeExceeded) {
                        results.add(result);
                        continue;
                    }
                    if (i >= probe.entries().size()) {
                        if (i == probe.failedTransactionIndex()) {
                            result.put("allowed", false);
                            result.put("reject-reason", testAcceptRejectReason(probe.rejectReason()));
                            result.put("reject-details", probe.rejectDetails());
                        }
                        results.add(result);
                        continue;
                    }
                    var entry = probe.entries().get(i);
                    // Core caps the base fee, independent of mining fee deltas,
                    // against CFeeRate::GetFee's rounded-up satoshi amount.
                    if (maxFeeRate != 0 && entry.fee() > new FeeRate(maxFeeRate).feeForVSize(entry.virtualSize())) {
                        result.put("allowed", false);
                        result.put("reject-reason", "max-fee-exceeded");
                        maxFeeExceeded = true;
                        results.add(result);
                        continue;
                    }
                    result.put("allowed", true);
                    result.put("vsize", entry.virtualSize());
                    result.put("vsize_adjusted", entry.virtualSize());
                    result.put("vsize_bip141", ru.bitcoin.node.consensus.transaction.TransactionWeight.virtualSize(entry.weight()));
                    var fees = new LinkedHashMap<String, Object>();
                    fees.put("base", satoshisToBtc(entry.fee()));
                    fees.put("effective-feerate", satoshisToBtc(entry.feeRate().satoshisPerKiloByte()));
                    fees.put("effective-includes", List.of(tx.wtxId().toDisplayHex()));
                    result.put("fees", fees);
                    results.add(result);
                }
                yield results;
            }
            case "submitpackage" -> {
                if (params.isEmpty() || params.size() > 3 || !(params.getFirst() instanceof List<?> rawPackage))
                    throw new RpcException(-32602, "Expected package array and optional maxfeerate/maxburnamount");
                if (rawPackage.isEmpty() || rawPackage.size() > 25)
                    throw new RpcException(-8, "Array must contain between 1 and 25 transactions.");
                long maxFeeRate = parseMaxFeeRate(params, 1);
                long maxBurnAmount = parseMoneyParam(params, 2, DEFAULT_MAX_BURN_AMOUNT, "maxburnamount");
                List<Transaction> transactions = new ArrayList<>(rawPackage.size());
                for (Object raw : rawPackage) {
                    var transaction = parsePackageTransaction(raw);
                    for (var output : transaction.outputs()) if (packageBurnOutput(output.scriptPubKey()) && output.value() > maxBurnAmount)
                        throw new RpcException(-25, "Unspendable output exceeds maximum configured by user (maxburnamount)");
                    transactions.add(transaction);
                }
                try {
                    if (!ru.bitcoin.node.mempool.Mempool.isSubmissionTopologyAllowed(transactions))
                        throw new RpcException(-25, "package topology disallowed. not child-with-parents or parents depend on each other.");
                    var submission = relay.submitPackage(transactions, maxFeeRate);
                    var txResults = new LinkedHashMap<String, Object>();
                    for (Transaction tx : transactions) {
                        var txResult = submission.transactions().get(tx.wtxId());
                        var result = new LinkedHashMap<String, Object>();
                        result.put("txid", tx.txId().toDisplayHex());
                        if (txResult.error() != null) {
                            result.put("error", txResult.error());
                            txResults.put(tx.wtxId().toDisplayHex(), result);
                            continue;
                        }
                        var entry = txResult.entry();
                        if (!entry.transaction().wtxId().equals(tx.wtxId())) {
                            result.put("other-wtxid", entry.transaction().wtxId().toDisplayHex());
                            txResults.put(tx.wtxId().toDisplayHex(), result);
                            continue;
                        }
                        result.put("vsize", entry.virtualSize());
                        result.put("vsize_adjusted", entry.virtualSize());
                        result.put("vsize_bip141", ru.bitcoin.node.consensus.transaction.TransactionWeight.virtualSize(entry.weight()));
                        var fees = new LinkedHashMap<String, Object>();
                        fees.put("base", satoshisToBtc(entry.fee()));
                        if (!txResult.known()) {
                            fees.put("effective-feerate", satoshisToBtc(txResult.effectiveFeeRate()));
                            fees.put("effective-includes", txResult.effectiveIncludes().stream().map(Hash256::toDisplayHex).toList());
                        }
                        result.put("fees", fees);
                        txResults.put(tx.wtxId().toDisplayHex(), result);
                    }
                    var result = new LinkedHashMap<String, Object>();
                    result.put("package_msg", submission.packageMessage());
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
            case "createrawtransaction" -> HexFormat.of().formatHex(TransactionSerializer.serialize(
                    TransactionCreation.construct(params, validation.networkParameters())));
            case "createmultisig" -> MultisigCreation.create(params, validation.networkParameters());
            case "signmessagewithprivkey" -> MessageSignatures.sign((String) params.get(0), (String) params.get(1), validation.networkParameters());
            case "verifymessage" -> MessageSignatures.verify((String) params.get(0), (String) params.get(1), (String) params.get(2), validation.networkParameters());
            case "decodepsbt" -> PsbtJson.decode(PsbtDocument.parse((String) params.getFirst()), validation.networkParameters());
            case "combinepsbt" -> PsbtCodec.combine((List<?>) params.getFirst());
            case "joinpsbts" -> PsbtCodec.join((List<?>) params.getFirst());
            case "finalizepsbt" -> PsbtFinalizer.finish((String) params.getFirst(),
                    !Boolean.FALSE.equals(TransactionCreation.optional(params,1)));
            case "analyzepsbt" -> PsbtAnalyzer.analyze((String) params.getFirst());
            case "utxoupdatepsbt" -> PsbtUpdater.update((String)params.getFirst(),
                    TransactionCreation.optional(params,1) instanceof List<?> descriptors?descriptors:List.of(),
                    validation.networkParameters(),new PsbtUpdater.Coins(){
                        public java.util.Optional<Transaction> transaction(Hash256 txid){
                            var indexed=validation.indexedTransaction(txid).map(ru.bitcoin.node.app.NodeValidationService.IndexedTransaction::transaction);
                            return indexed.isPresent()?indexed:validation.mempoolEntry(txid).map(ru.bitcoin.node.mempool.MempoolEntry::transaction);
                        }
                        public java.util.Optional<ru.bitcoin.node.protocol.transaction.TxOut> coin(ru.bitcoin.node.protocol.transaction.OutPoint point){
                            return validation.txOut(point.transactionId(),point.outputIndex().value(),true)
                                    .map(coin->new ru.bitcoin.node.protocol.transaction.TxOut(coin.amount(),coin.scriptPubKey()));
                        }
                    });
            case "getdescriptorinfo" -> DescriptorRpc.info((String) params.getFirst(),validation.networkParameters());
            case "deriveaddresses" -> DescriptorRpc.derive(params,validation.networkParameters());
            case "decodescript" -> ScriptDecoding.decode((String)params.getFirst(),validation.networkParameters());
            case "validateaddress" -> AddressValidation.validate((String)params.getFirst(),validation.networkParameters());
            case "createpsbt" -> PsbtCreator.create(TransactionCreation.construct(params, validation.networkParameters()),
                    TransactionCreation.optional(params, 5));
            case "converttopsbt" -> {
                Transaction transaction;
                try { transaction = decodeTransaction((String) params.getFirst(), (Boolean) TransactionCreation.optional(params, 2)); }
                catch (IllegalArgumentException | java.nio.BufferUnderflowException invalid) { throw new RpcException(-22, "TX decode failed"); }
                boolean permit = Boolean.TRUE.equals(TransactionCreation.optional(params, 1));
                var inputs = new ArrayList<ru.bitcoin.node.protocol.transaction.TxIn>();
                for (var input : transaction.inputs()) {
                    if (!permit && (input.scriptSigLength() != 0 || input.witness().size() != 0))
                        throw new RpcException(-22, "Inputs must not have scriptSigs and scriptWitnesses");
                    inputs.add(new ru.bitcoin.node.protocol.transaction.TxIn(input.previousOutput(), new byte[0], input.sequence()));
                }
                transaction = new Transaction(transaction.version(), inputs, transaction.outputs(), transaction.lockTime());
                yield PsbtCreator.create(transaction, TransactionCreation.optional(params, 3));
            }
            case "decoderawtransaction" -> {
                String raw = (String) params.getFirst();
                try {
                    Boolean witness = params.size() < 2 || params.get(1) == null ? null : (Boolean) params.get(1);
                    Transaction transaction = decodeTransaction(raw, witness);
                    var json = transactionJson(transaction, null, null); json.remove("hex");
                    yield json;
                } catch (IllegalArgumentException | java.nio.BufferUnderflowException exception) {
                    throw new RpcException(-22, "TX decode failed");
                }
            }
            case "getrawtransaction" -> {
                Hash256 txid = waitHash((String) params.getFirst(), "parameter 1");
                if (txid.equals(validation.storedBlockIndexLookup().find(validation.networkParameters().genesisBlockHash()).header().merkleRoot()))
                    throw new RpcException(-5, "The genesis block coinbase is not considered an ordinary transaction and cannot be retrieved");
                int verbosity = intOrBooleanVerbosity(params, 1, 0);
                Transaction transaction;
                NodeValidationService.ActiveBlockInfo blockInfo = null;
                NodeValidationService.RpcBlockView view = null;
                Boolean inActiveChain = null;
                if (params.size() >= 3 && params.get(2) != null) {
                    Hash256 blockHash = waitHash((String) params.get(2), "parameter 3");
                    view = validation.rpcBlockView(blockHash, verbosity > 1)
                            .orElseThrow(() -> new RpcException(-5, "Block hash not found"));
                    blockInfo = view.info();
                    if (view.block().isEmpty()) throw new RpcException(-1, "Block not available");
                    transaction = view.block().orElseThrow().transactions().stream().filter(tx -> tx.txId().equals(txid)).findFirst()
                            .orElseThrow(() -> new RpcException(-5, "No such transaction found in the provided block. Use gettransaction for wallet transactions."));
                    inActiveChain = blockInfo.confirmations() >= 0;
                } else {
                    var mempool = validation.mempoolEntry(txid);
                    if (mempool.isPresent()) transaction = mempool.orElseThrow().transaction();
                    else {
                        var indexed = validation.indexedTransaction(txid);
                        if (indexed.isEmpty()) {
                            String message = validation.txIndexEnabled() ? "No such mempool or blockchain transaction"
                                    : "No such mempool transaction. Use -txindex or provide a block hash to enable blockchain transaction queries";
                            throw new RpcException(-5, message + ". Use gettransaction for wallet transactions.");
                        }
                        transaction = indexed.orElseThrow().transaction(); blockInfo = indexed.orElseThrow().blockInfo();
                    }
                }
                if (verbosity <= 0) yield HexFormat.of().formatHex(TransactionSerializer.serialize(transaction));
                var json = transactionJson(transaction, blockInfo, inActiveChain);
                if (blockInfo == null) validation.mempoolEntry(txid).ifPresent(entry -> json.put("vsize_adjusted", entry.virtualSize()));
                if (verbosity > 1 && !transaction.isCoinbase() && blockInfo != null) {
                    if (view == null) view = validation.rpcBlockView(blockInfo.index().hash(), true).orElse(null);
                    if (view != null && view.undo().isPresent() && view.block().isPresent()) {
                        var transactions = view.block().orElseThrow().transactions();
                        for (int i = 1; i < transactions.size(); i++) if (transactions.get(i).txId().equals(txid)) {
                            addUndo(transaction, json, view.undo().orElseThrow().transactions().get(i - 1).spentOutputs(), true); break;
                        }
                    }
                }
                yield json;
            }
            case "prioritisetransaction" -> {
                if (params.size() != 3)
                    throw new RpcException(-32602, "Expected txid, dummy and fee_delta");
                RpcMethods.validateParameterTypes(method, params);
                String txidText = (String) params.getFirst();
                Hash256 txid = waitHash(txidText, "txid");
                long delta;
                try { delta = Long.parseLong(params.get(2).toString()); }
                catch (NumberFormatException error) { throw new RpcException(-1, "JSON integer out of range"); }
                if (params.get(1) != null && ((Number) params.get(1)).doubleValue() != 0.0)
                    throw new RpcException(-8, "Priority is no longer supported, dummy argument to prioritisetransaction must be 0.");
                try { validation.prioritiseTransaction(txid, delta); }
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
                result.add(Map.of("weight", 0L, "fee", satoshisToBtc(0)));
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
                result.put("scriptPubKey", ScriptJson.output(coin.scriptPubKey(), validation.networkParameters()));
                result.put("coinbase", coin.coinbase());
                yield result;
            }
            case "gettxoutsetinfo" -> {
                String hashType = params.isEmpty() || params.getFirst() == null ? "hash_serialized_3" : (String) params.getFirst();
                if (!hashType.equals("hash_serialized_3") && !hashType.equals("muhash") && !hashType.equals("none"))
                    throw new RpcException(-8, "'" + hashType + "' is not a supported hash_type");
                Object hashOrHeight = params.size() > 1 ? params.get(1) : null;
                boolean useIndex = booleanParam(params, 2, true);

                NodeValidationService.UtxoSetInfo stats;
                boolean usedIndex = false;
                if (hashOrHeight != null) {
                    if (!validation.coinStatsIndexEnabled())
                        throw new RpcException(-8, "Querying specific block heights requires coinstatsindex");
                    if (hashType.equals("hash_serialized_3"))
                        throw new RpcException(-8, "hash_serialized_3 hash type cannot be queried for a specific block");
                    if (!useIndex)
                        throw new RpcException(-8, "Cannot set use_index to false when querying for a specific block");
                    if (!(hashOrHeight instanceof String) && !(hashOrHeight instanceof Number))
                        throw new RpcException(-3, "JSON value of type " + (hashOrHeight instanceof Boolean ? "bool" : hashOrHeight instanceof List<?> ? "array" : "object")
                                + " is not of expected type string");
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
            case "getblockstats" -> {
                Object target = params.getFirst();
                Hash256 hash;
                if (target instanceof Number) {
                    int height = waitInteger(params, 0, 0);
                    if (height < 0) throw new RpcException(-8, "Target block height " + height + " is negative");
                    var info = validation.activeBlockInfo(height);
                    if (info.isEmpty()) throw new RpcException(-8, "Target block height " + height + " after current tip " + validation.activeTip().height());
                    hash = info.orElseThrow().index().hash();
                } else {
                    if (!(target instanceof String text)) throw new RpcException(-3, "JSON value of type " + jsonType(target) + " is not of expected type string");
                    hash = waitHash(text, "hash_or_height");
                }
                if (validation.blockInfo(hash).isEmpty()) throw new RpcException(-5, "Block not found");
                var selected = new TreeSet<String>((left, right) -> Arrays.compareUnsigned(left.getBytes(StandardCharsets.UTF_8), right.getBytes(StandardCharsets.UTF_8)));
                if (params.size() > 1 && params.get(1) != null) for (Object key : (List<?>) params.get(1)) {
                    if (!(key instanceof String text)) throw new RpcException(-3, "JSON value of type " + jsonType(key) + " is not of expected type string");
                    selected.add(text);
                }
                var view = validation.rpcBlockView(hash, true).orElseThrow(() -> new RpcException(-5, "Block not found"));
                if (view.block().isEmpty()) throw new RpcException(-1, validation.isPrunedBlock(hash)
                        ? "Block not available (pruned data)" : "Block not available (not fully downloaded)");
                yield BlockStatistics.calculate(view, validation.blockMedianTimePast(view.info().index()), validation.networkParameters(), selected);
            }
            case "gettxoutproof" -> {
                var ids = (List<?>) params.getFirst();
                if (ids.isEmpty()) throw new RpcException(-8, "Parameter 'txids' cannot be empty");
                var wanted = new HashSet<Hash256>();
                for (Object id : ids) {
                    if (!(id instanceof String text)) throw new RpcException(-3, "JSON value of type " + (id == null ? "null" : id instanceof Boolean ? "bool" : id instanceof Number ? "number" : id instanceof List<?> ? "array" : "object") + " is not of expected type string");
                    var hash = waitHash(text, "txid");
                    if (!wanted.add(hash)) throw new RpcException(-8, "Invalid parameter, duplicated txid: " + text);
                }
                Hash256 block = params.size() < 2 || params.get(1) == null ? null : waitHash((String) params.get(1), "blockhash");
                try { yield HexFormat.of().formatHex(validation.transactionProof(wanted, block)); }
                catch (NodeValidationService.TransactionProofException error) {
                    throw switch (error.failure()) {
                        case BLOCK_NOT_FOUND -> new RpcException(-5, "Block not found");
                        case TRANSACTION_NOT_IN_BLOCK -> new RpcException(-5, "Transaction not yet in block");
                        case TRANSACTIONS_MISSING -> new RpcException(-5, "Not all transactions found in specified or retrieved block");
                        case PRUNED -> new RpcException(-1, "Block not available (pruned data)");
                        case NOT_DOWNLOADED -> new RpcException(-1, "Block not available (not fully downloaded)");
                    };
                }
            }
            case "verifytxoutproof" -> {
                String hex = (String) params.getFirst();
                if (hex.isEmpty() || !hex.matches("(?:[0-9a-fA-F]{2})+")) throw new RpcException(-8, "proof must be hexadecimal string (not '" + hex + "')");
                final ru.bitcoin.node.protocol.block.MerkleBlockProof.Extracted proof;
                try { proof = ru.bitcoin.node.protocol.block.MerkleBlockProof.extract(HexFormat.of().parseHex(hex)); }
                catch (IllegalArgumentException | IndexOutOfBoundsException error) {
                    String message = error.getMessage() != null && error.getMessage().contains("Non-canonical")
                            ? "non-canonical ReadCompactSize()" : error.getMessage() != null && error.getMessage().contains("size too large")
                            ? "ReadCompactSize(): size too large" : "SpanReader::read(): end of data";
                    throw new RpcException(-1, message + ": iostream error");
                }
                try { yield validation.verifyTransactionProof(proof).stream().map(Hash256::toDisplayHex).toList(); }
                catch (NodeValidationService.TransactionProofException error) { throw new RpcException(-5, "Block not found in chain"); }
            }
            case "getblockheader" -> {
                if (params.isEmpty() || params.size() > 2 || !(params.getFirst() instanceof String hashText))
                    throw new RpcException(-32602, "Expected block hash and optional verbosity");
                boolean verbose = booleanParam(params, 1, true);
                Hash256 hash = waitHash(hashText, "hash");
                var info = validation.blockInfo(hash)
                        .orElseThrow(() -> new RpcException(-5, "Block not found"));
                if (!verbose) {
                    yield HexFormat.of().formatHex(BlockHeaderSerializer.serialize(info.index().header()));
                }
                yield blockHeaderJson(info);
            }
            case "getblock" -> {
                if (params.isEmpty() || params.size() > 2 || !(params.getFirst() instanceof String hashText))
                    throw new RpcException(-32602, "Expected block hash and optional verbosity");
                Hash256 hash = waitHash(hashText, "blockhash");
                int verbosity = intOrBooleanVerbosity(params, 1, 1);
                var view = validation.rpcBlockView(hash, verbosity >= 2)
                        .orElseThrow(() -> new RpcException(-5, "Block not found"));
                var info = view.info();
                var block = view.block();
                if (block.isEmpty()) {
                    if (validation.isPrunedBlock(hash))
                        throw new RpcException(-1, "Block not available (pruned data)");
                    throw new RpcException(-1, "Block not available (not fully downloaded)");
                }
                if (verbosity <= 0) {
                    yield HexFormat.of().formatHex(BlockSerializer.serialize(block.get()));
                }
                var result = new LinkedHashMap<String, Object>(blockHeaderJson(info));
                result.put("size", BlockSerializer.serialize(block.get()).length);
                result.put("strippedsize", BlockSerializer.serializeLegacy(block.get()).length);
                result.put("weight", blockWeight(block.get()));
                result.put("nTx", block.get().transactions().size());
                result.put("coinbase_tx", coinbaseJson(block.get().transactions().getFirst()));
                result.put("tx", verbosity == 1
                        ? block.get().transactions().stream().map(tx -> tx.txId().toDisplayHex()).toList()
                        : blockTransactionsJson(block.orElseThrow(), view.undo().orElse(null), verbosity));
                yield result;
            }
            case "getconnectioncount" -> requirePeerManager().size();
            case "getpeerinfo" -> requirePeerManager().managedPeers().stream().map(managed -> {
                var peer = managed.peer();
                var info = new LinkedHashMap<String, Object>();
                var remote = peer.remoteAddress();
                info.put("addr", remote == null ? "" : remote.getHostString() + ":" + remote.getPort());
                info.put("inbound", peer.isInboundConnection());
                var headerSync = sync == null ? null : sync.headerSyncService();
                info.put("presynced_headers", headerSync == null ? -1L : headerSync.presyncedHeight(peer));
                info.put("connection_type", managed.role().name().toLowerCase(Locale.ROOT));
                info.put("permissions", List.of(ru.bitcoin.node.p2p.PeerPermission.NOBAN, ru.bitcoin.node.p2p.PeerPermission.FORCERELAY, ru.bitcoin.node.p2p.PeerPermission.RELAY, ru.bitcoin.node.p2p.PeerPermission.MEMPOOL, ru.bitcoin.node.p2p.PeerPermission.DOWNLOAD, ru.bitcoin.node.p2p.PeerPermission.ADDR)
                        .stream().filter(peer::hasPermission).map(permission -> permission.name().toLowerCase(Locale.ROOT)).toList());
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
                    if (state.snapshot()) item.put("snapshot_blockhash", state.snapshotBlockHash().toDisplayHex());
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
                validation.backgroundValidationReport().ifPresent(background -> {
                    var report = new LinkedHashMap<String,Object>();
                    report.put("snapshotheight", background.snapshotHeight());
                    report.put("blocks", background.blocks());
                    report.put("bestblockhash", background.bestBlockHash().toDisplayHex());
                    report.put("mediantime", background.medianTime());
                    report.put("chainwork", String.format("%064x", background.chainWork()));
                    report.put("verificationprogress", background.verificationProgress());
                    info.put("backgroundvalidation", report);
                });
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
                info.put("usage", validation.mempoolMemoryUsage());
                info.put("total_fee", satoshisToBtc(entries.stream().mapToLong(ru.bitcoin.node.mempool.MempoolEntry::fee).sum()));
                info.put("maxmempool", MempoolLimits.DEFAULT.maxPoolMemoryBytes());
                info.put("mempoolminfee", satoshisPerKvBToBtcPerKvB(validation.feeFilterRate()));
                info.put("minrelaytxfee", satoshisPerKvBToBtcPerKvB(validation.minimumRelayFeeRate()));
                info.put("incrementalrelayfee", satoshisPerKvBToBtcPerKvB(MempoolLimits.DEFAULT.incrementalRelaySatPerKvB()));
                info.put("unbroadcastcount", validation.unbroadcastMempoolCount());
                info.put("limitclustercount", MempoolLimits.DEFAULT.clusterCount());
                info.put("limitclustersize", MempoolLimits.DEFAULT.clusterVirtualBytes());
                info.put("optimal", validation.mempoolOptimal());
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
                info.put("inv_buckets", relay.inventoryBucketInfo());
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
        long bits = tip.header().bits().value(); int shift = (int) (bits >>> 24);
        double difficulty = 0x0000ffff / (double) (bits & 0x00ffffff);
        while (shift < 29) { difficulty *= 256; shift++; }
        while (shift > 29) { difficulty /= 256; shift--; }
        return new java.math.BigDecimal(difficulty).round(java.math.MathContext.DECIMAL64).doubleValue();
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
        if (value instanceof Number) return waitInteger(params, index, defaultValue);
        throw new RpcException(-3, "JSON value of type " + jsonType(value) + " is not of expected type number");
    }

    private List<Map<String, Object>> blockTransactionsJson(
            ru.bitcoin.node.protocol.block.Block block,
            ru.bitcoin.node.storage.undo.BlockUndoData undo, int verbosity) {
        var transactions = block.transactions();
        if (undo != null && undo.transactions().size() != transactions.size() - 1)
            throw new RpcException(-32603, "Block undo transaction count mismatch");
        var result = new ArrayList<Map<String, Object>>(transactions.size());
        for (int i = 0; i < transactions.size(); i++) {
            var transaction = transactions.get(i);
            var json = transactionJson(transaction, null, null);
            if (i > 0 && undo != null) {
                addUndo(transaction, json, undo.transactions().get(i - 1).spentOutputs(), verbosity >= 3);
            }
            result.add(json);
        }
        return result;
    }

    private void addUndo(Transaction transaction, Map<String, Object> json,
                         List<ru.bitcoin.node.storage.utxo.StoredUtxo> coins, boolean includePrevouts) {
        if (coins.size() != transaction.inputs().size())
            throw new RpcException(-32603, "Block undo input count mismatch");
        long totalIn = 0;
        @SuppressWarnings("unchecked")
        var inputs = (List<Map<String, Object>>) json.get("vin");
        for (int j = 0; j < coins.size(); j++) {
            var coin = coins.get(j);
            totalIn = Math.addExact(totalIn, coin.amount());
            if (includePrevouts) inputs.get(j).put("prevout", Map.of(
                    "generated", coin.coinbase(), "height", coin.height(),
                    "value", satoshisToBtc(coin.amount()),
                    "scriptPubKey", ScriptJson.output(coin.scriptPubKey(), validation.networkParameters())));
        }
        long totalOut = 0;
        for (var output : transaction.outputs()) totalOut = Math.addExact(totalOut, output.value());
        long fee = Math.subtractExact(totalIn, totalOut);
        if (fee < 0 || fee > ru.bitcoin.node.storage.utxo.StoredUtxo.MAX_MONEY)
            throw new RpcException(-32603, "Block undo fee out of range");
        json.put("fee", satoshisToBtc(fee));
    }

    private static Map<String, Object> coinbaseJson(Transaction coinbase) {
        var input = coinbase.inputs().getFirst();
        var result = new LinkedHashMap<String, Object>();
        result.put("version", coinbase.version());
        result.put("locktime", coinbase.lockTime().value());
        result.put("sequence", input.sequence().value());
        result.put("coinbase", HexFormat.of().formatHex(input.scriptSig()));
        if (!input.witness().isEmpty())
            result.put("witness", HexFormat.of().formatHex(input.witness().items().getFirst()));
        return result;
    }

    private Map<String, Object> transactionJson(
            Transaction transaction,
            NodeValidationService.ActiveBlockInfo blockInfo,
            Boolean inActiveChain
    ) {
        var result = transactionJson(transaction, validation.networkParameters());
        if (blockInfo != null) {
            if (inActiveChain != null) result.put("in_active_chain", inActiveChain);
            result.put("blockhash", blockInfo.index().hash().toDisplayHex());
            result.put("confirmations", Math.max(0L, blockInfo.confirmations()));
            if (blockInfo.confirmations() >= 0) {
                result.put("time", blockInfo.index().header().timestamp().value());
                result.put("blocktime", blockInfo.index().header().timestamp().value());
            }
        }
        return result;
    }

    static Map<String, Object> transactionJson(Transaction transaction, ru.bitcoin.node.protocol.network.NetworkParameters parameters) {
        byte[] serialized = TransactionSerializer.serialize(transaction);
        long weight = TransactionWeight.calculate(transaction);
        var result = new LinkedHashMap<String, Object>();
        result.put("txid", transaction.txId().toDisplayHex());
        result.put("hash", transaction.wtxId().toDisplayHex());
        result.put("version", Integer.toUnsignedLong(transaction.version()));
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
                item.put("scriptSig", Map.of("asm", ScriptJson.assembly(input.scriptSig(), true), "hex", HexFormat.of().formatHex(input.scriptSig())));
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
            item.put("scriptPubKey", ScriptJson.output(output.scriptPubKey(), parameters));
            vout.add(item);
        }
        result.put("vout", vout);
        result.put("hex", HexFormat.of().formatHex(serialized));

        return result;
    }

    private static Transaction decodeTransaction(String hex, Boolean witness) {
        if (hex.isEmpty() || !hex.matches("(?:[0-9a-fA-F]{2})+")) throw new IllegalArgumentException("Transaction hex");
        byte[] bytes = HexFormat.of().parseHex(hex); Transaction extended = null, legacy = null;
        if (witness == null || witness) {
            try { extended = TransactionParser.parse(bytes, true); if (saneTransactionScripts(extended)) return extended; }
            catch (IllegalArgumentException | IndexOutOfBoundsException ignored) { }
        }
        if (witness == null || !witness) {
            try { legacy = TransactionParser.parse(bytes, false); if (saneTransactionScripts(legacy)) return legacy; }
            catch (IllegalArgumentException | IndexOutOfBoundsException ignored) { }
        }
        if (extended != null) return extended;
        if (legacy != null) return legacy;
        throw new IllegalArgumentException("Transaction decode failed");
    }

    private static boolean saneTransactionScripts(Transaction transaction) {
        var scripts = new ArrayList<byte[]>();
        if (!transaction.isCoinbase()) transaction.inputs().forEach(input -> scripts.add(input.scriptSig()));
        transaction.outputs().forEach(output -> scripts.add(output.scriptPubKey()));
        for (byte[] script : scripts) {
            if (script.length > 10000) return false;
            try { for (var op : ru.bitcoin.node.script.ScriptParser.parse(script))
                if (op.opcode() > 0xba || op.isPushData() && op.data() != null && op.data().length > 520) return false;
            } catch (ru.bitcoin.node.script.ScriptParseException error) { return false; }
        }
        return true;
    }

    private static Transaction parsePackageTransaction(Object value) {
        if (!(value instanceof String hex)) {
            String type = value == null ? "null" : value instanceof Number ? "number"
                    : value instanceof Boolean ? "bool" : value instanceof List<?> ? "array" : "object";
            throw new RpcException(-3, "JSON value of type " + type + " is not of expected type string");
        }
        return parseRpcTransaction(hex, "TX decode failed: " + hex + " Make sure the tx has at least one input.");
    }

    private static Transaction parseRpcTransaction(String hex, String decodeError) {
        try {
            var transaction = TransactionParser.parse(HexFormat.of().parseHex(hex));
            if (transaction.inputs().isEmpty()) throw new IllegalArgumentException("Transaction has no inputs");
            return transaction;
        } catch (IllegalArgumentException | java.nio.BufferUnderflowException failure) {
            throw new RpcException(-22, decodeError);
        }
    }

    private static long parseMaxFeeRate(List<?> params, int index) {
        long rate = parseMoneyParam(params, index, DEFAULT_MAX_RAW_TX_FEE_RATE, "maxfeerate");
        if (rate >= MAX_RAW_TX_FEE_RATE) throw new RpcException(-8, "Fee rates larger than or equal to 1BTC/kvB are not accepted");
        return rate;
    }

    static long parseMoneyParam(List<?> params, int index, long defaultValue, String name) {
        if (params.size() <= index || params.get(index) == null) return defaultValue;
        Object value = params.get(index);
        if (!(value instanceof Number) && !(value instanceof String))
            throw new RpcException(-3, "Amount is not a number or string");
        String text = value.toString();
        if (!text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))
            throw new RpcException(-3, "Invalid amount");
        final long amount;
        try {
            var satoshis = new java.math.BigDecimal(text).scaleByPowerOfTen(8);
            // ParseFixedPoint bounds the absolute scaled value below 10^18,
            // independently of the narrower consensus money range.
            if (satoshis.abs().compareTo(java.math.BigDecimal.valueOf(1_000_000_000_000_000_000L)) >= 0
                    || satoshis.stripTrailingZeros().scale() > 0)
                throw new ArithmeticException("Invalid fixed-point amount");
            amount = satoshis.longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            throw new RpcException(-3, "Invalid amount");
        }
        if (amount < 0 || amount > ru.bitcoin.node.consensus.money.Money.MAX_MONEY)
            throw new RpcException(-3, "Amount out of range");
        return amount;
    }

    private static boolean packageBurnOutput(byte[] script) {
        if (UnspendableScript.isUnspendable(script)) return true;
        try {
            for (var instruction : ru.bitcoin.node.script.ScriptParser.parse(script))
                if (instruction.opcode() > 0xba || instruction.dataLength() > 520) return true;
            return false;
        } catch (ru.bitcoin.node.script.ScriptParseException malformed) { return true; }
    }

    private static String testAcceptRejectReason(String reason) {
        return switch (reason) {
            case "cluster-count-limit", "cluster-size-limit" -> "too-large-cluster";
            case "mempool-min-fee" -> "mempool min fee not met";
            default -> reason.startsWith("Minimum relay fee not met:") ? "min relay fee not met" : reason;
        };
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
        result.put("mediantime", validation.blockMedianTimePast(index));
        result.put("target", String.format("%064x", ru.bitcoin.node.consensus.pow.CompactTarget.decode(header.bits().value())));
        result.put("difficulty", difficulty(index));
        result.put("nTx", validation.blockTransactionCount(index.hash()));
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

    private static String jsonType(Object value) {
        return value == null ? "null" : value instanceof Boolean ? "bool" : value instanceof Number ? "number"
                : value instanceof String ? "string" : value instanceof List<?> ? "array" : "object";
    }

    private static boolean booleanParam(List<?> params, int index, boolean defaultValue) {
        if (params.size() <= index || params.get(index) == null) return defaultValue;
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
        var methods = RpcMethods.names();
        if (command == null || command.isEmpty()) return RpcMethods.helpList();
        if (!methods.contains(command)) return "help: unknown command: " + command;
        String canonical = RpcMethods.canonicalHelp(command);
        if (canonical != null) return canonical;
        return command + " - supported by java-bitcoin-node RPC";
    }

    private Object waitForChain(String method, List<?> params) throws InterruptedException {
        RpcMethods.validateChainWaitParameters(method, params);
        java.util.function.Predicate<ru.bitcoin.node.chain.BlockIndex> satisfied;
        int timeout;
        if (method.equals("waitfornewblock")) {
            timeout = waitInteger(params, 0, 0);
            if (timeout < 0) throw new RpcException(-1, "Negative timeout");
            var current = validation.activeTip().hash();
            var tip = params.size() < 2 || params.get(1) == null ? current : waitHash((String) params.get(1), "current_tip");
            satisfied = index -> !index.hash().equals(tip);
        } else {
            var hash = method.equals("waitforblock") ? waitHash((String) params.getFirst(), "blockhash") : null;
            int height = method.equals("waitforblockheight") ? waitInteger(params, 0, 0) : 0;
            timeout = waitInteger(params, 1, 0);
            if (timeout < 0) throw new RpcException(-1, "Negative timeout");
            satisfied = method.equals("waitforblock") ? index -> index.hash().equals(hash) : index -> index.height() >= height;
        }
        tipWaiters.incrementAndGet();
        try {
            var tip = validation.awaitChainTip(satisfied, timeout, () -> running);
            return Map.of("hash", tip.hash().toDisplayHex(), "height", tip.height());
        } finally { tipWaiters.decrementAndGet(); }
    }

    private static int waitInteger(List<?> params, int position, int fallback) {
        if (params.size() <= position || params.get(position) == null) return fallback;
        try { return Integer.parseInt(params.get(position).toString()); }
        catch (NumberFormatException error) { throw new RpcException(-1, "JSON integer out of range"); }
    }

    private static Hash256 waitHash(String value, String name) {
        int length = value.getBytes(StandardCharsets.UTF_8).length;
        if (length != 64) throw new RpcException(-8, name + " must be of length 64 (not " + length + ", for '" + value + "')");
        if (!value.matches("[0-9a-fA-F]{64}")) throw new RpcException(-8, name + " must be hexadecimal string (not '" + value + "')");
        return Hash256.fromDisplayHex(value);
    }

    private void respondNoContent(HttpExchange exchange) throws IOException {
        exchange.sendResponseHeaders(204, -1);
    }

    private void respond(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = (mapper.writeValueAsString(value) + "\n").getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    @Override
    public void close() {
        boolean waiting = tipWaiters.get() != 0 || activeCommands.values().stream()
                .anyMatch(command -> Set.of("waitfornewblock", "waitforblock", "waitforblockheight").contains(command.method()));
        running = false;
        validation.wakeChainTipWaiters();
        mining.close();
        server.stop(waiting ? 1 : 0);
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
