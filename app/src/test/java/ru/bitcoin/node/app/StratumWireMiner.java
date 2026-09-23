package ru.bitcoin.node.app;

import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Test miner reconstructs headers strictly from wire messages, without MiningJob internals. */
final class StratumWireMiner implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    final Socket socket;
    private final BufferedReader input;
    private final Writer output;
    private final Queue<Map<?, ?>> notifications = new ArrayDeque<>();
    private int nextId;
    String extraNonce;
    int extraNonce2Size;
    java.math.BigDecimal difficulty;

    StratumWireMiner(int port) throws IOException {
        socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5000);
        input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        output = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
    }

    Map<?, ?> call(String method, List<?> params) throws IOException {
        int id = ++nextId;
        output.write(JSON.writeValueAsString(Map.of("id", id, "method", method, "params", params)) + "\n");
        output.flush();
        while (true) {
            var message = read();
            if (message.get("id") instanceof Number number && number.intValue() == id) return message;
            notifications.add(message);
        }
    }

    void subscribe() throws IOException {
        var response = call("mining.subscribe", List.of("java-test-miner/1.0"));
        if (response.get("error") != null) throw new IOException("Subscription rejected: " + response);
        var result = (List<?>) response.get("result");
        extraNonce = (String) result.get(1);
        extraNonce2Size = ((Number) result.get(2)).intValue();
    }

    List<?> job() throws IOException {
        while (true) {
            var message = notifications.isEmpty() ? read() : notifications.remove();
            if ("mining.set_difficulty".equals(message.get("method")))
                difficulty = new java.math.BigDecimal(((List<?>) message.get("params")).getFirst().toString());
            if ("mining.notify".equals(message.get("method"))) return (List<?>) message.get("params");
        }
    }

    record Solution(List<?> params, BlockHeader header) { }

    Solution solve(List<?> job, boolean block) {
        return solve(job, block, null, 0);
    }

    Solution solve(List<?> job, boolean block, String versionBits, int versionMask) {
        int version = (int)Long.parseUnsignedLong((String)job.get(5), 16);
        if (versionBits != null) version = (version & ~versionMask) | Integer.parseUnsignedInt(versionBits, 16);
        String extraNonce2 = "00".repeat(extraNonce2Size);
        byte[] coinbase = HexFormat.of().parseHex(job.get(2) + extraNonce + extraNonce2 + job.get(3));
        byte[] root = Hash256Digest.hashBytes(coinbase);
        for (var sibling : (List<?>) job.get(4)) {
            byte[] pair = Arrays.copyOf(root, 64);
            System.arraycopy(HexFormat.of().parseHex((String)sibling), 0, pair, 32, 32);
            root = Hash256Digest.hashBytes(pair);
        }
        byte[] previous = HexFormat.of().parseHex((String)job.get(1));
        // Decode Stratum's eight big-endian words to the block header's wire byte order.
        var words = java.nio.ByteBuffer.wrap(previous).asIntBuffer();
        var wire = java.nio.ByteBuffer.allocate(32).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        while (words.hasRemaining()) wire.putInt(words.get());
        for (long nonce = 0; nonce < 100_000; nonce++) {
            var header = new BlockHeader(version, new Hash256(wire.array()), new Hash256(root),
                    new UInt32(Long.parseUnsignedLong((String)job.get(7),16)), new UInt32(Long.parseUnsignedLong((String)job.get(6),16)), new UInt32(nonce));
            if (ProofOfWork.isValid(header, NetworkParametersRegistry.regtest()) == block) {
                var params = new ArrayList<Object>(List.of("miner.test", job.getFirst(), extraNonce2, job.get(7), String.format("%08x", nonce)));
                if (versionBits != null) params.add(versionBits);
                return new Solution(params, header);
            }
        }
        throw new AssertionError("Could not find regtest solution");
    }

    private Map<?, ?> read() throws IOException {
        String line = input.readLine();
        if (line == null) throw new EOFException("Stratum closed connection");
        return JSON.readValue(line, Map.class);
    }

    @Override public void close() throws IOException { socket.close(); }
}
