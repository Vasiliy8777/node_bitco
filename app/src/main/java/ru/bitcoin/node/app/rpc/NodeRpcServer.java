package ru.bitcoin.node.app.rpc;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;
import ru.bitcoin.node.app.NodeValidationService;
import ru.bitcoin.node.app.service.NodeRelayService;
import ru.bitcoin.node.app.sync.NodeSyncInfrastructure;
import ru.bitcoin.node.protocol.serialization.TransactionParser;
import ru.bitcoin.node.protocol.serialization.BlockHeaderSerializer;
import ru.bitcoin.node.protocol.serialization.BlockSerializer;
import ru.bitcoin.node.common.types.Hash256;

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

    public NodeRpcServer(InetSocketAddress address, String user, String password, MiningController mining,
                         NodeValidationService validation, NodeRelayService relay, NodeSyncInfrastructure sync,
                         BooleanSupplier ready) throws IOException {
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
            Object id = null;
            Object result = null;
            Object error = null;
            boolean acquired = false;
            try {
                Map<?, ?> request;
                try {
                    request = mapper.readValue(body, Map.class);
                } catch (RuntimeException exception) {
                    throw new RpcException(-32700, "Parse error");
                }
                if (request == null || !(request.get("method") instanceof String method))
                    throw new RpcException(-32600, "Invalid request");
                id = request.get("id");
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
                if (acquired) longPolls.release();
            }
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("result", result);
            response.put("error", error);
            response.put("id", id);
            respond(exchange, 200, response);
        }
    }

    @SuppressWarnings("unchecked")
    private Object dispatch(String method, List<?> params) throws InterruptedException {
        return switch (method) {
            case "getblocktemplate" -> {
                if (params.size() != 1 || !(params.getFirst() instanceof Map<?, ?>))
                    throw new RpcException(-32602, "Expected one template request object");
                yield mining.getBlockTemplate((Map<String, Object>) params.getFirst());
            }
            case "submitblock" -> mining.submitBlock(stringParam(params));
            case "sendrawtransaction" -> {
                try {
                    yield relay.submitTransaction(TransactionParser.parse(HexFormat.of().parseHex(stringParam(params)))).toDisplayHex();
                } catch (ru.bitcoin.node.mempool.MempoolAdmissionException
                         | ru.bitcoin.node.consensus.transaction.TransactionValidationException
                         | ru.bitcoin.node.script.ScriptExecutionException |
                         ru.bitcoin.node.script.ScriptParseException exception) {
                    throw new RpcException(-26, exception.getMessage());
                }
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
            case "getblockchaininfo" -> {
                var tip = validation.activeTip();
                var prune = validation.pruneInfo();
                var info = new LinkedHashMap<String, Object>();
                info.put("blocks", tip.height());
                info.put("headers", sync.headerChainState().bestHeaderTip().height());
                info.put("bestblockhash", tip.hash().toDisplayHex());
                info.put("chainwork", String.format("%064x", tip.chainWork()));
                info.put("initialblockdownload", validation.isInitialBlockDownload());
                info.put("pruned", prune.enabled());
                if (prune.enabled()) {
                    info.put("pruneheight", prune.pruneHeight());
                    info.put("automatic_pruning", true);
                    info.put("prune_target_size", prune.targetBytes());
                }
                yield info;
            }
            case "getmininginfo" ->
                    Map.of("blocks", validation.activeTip().height(), "pooledtx", validation.mempoolEntries().size(),
                            "miningready", ready.getAsBoolean());
            case "getrawmempool" ->
                    validation.mempoolEntries().stream().map(entry -> entry.transaction().txId().toDisplayHex()).toList();
            default -> throw new RpcException(-32601, "Method not found");
        };
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
