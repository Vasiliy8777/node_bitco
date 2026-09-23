package ru.bitcoin.node.stratum;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.stratum.job.*;
import ru.bitcoin.node.stratum.protocol.StratumException;
import tools.jackson.databind.json.JsonMapper;
import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One subscribed extranonce and one authorized worker per TCP connection. */
public final class StratumSession implements AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final StratumServer server;
    private final Socket socket;
    private final String extraNonce;
    private final byte[] extraNonceBytes;
    private final BlockingQueue<String> outgoing = new ArrayBlockingQueue<>(32);
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Hash256> seenShares = new HashSet<>();
    private final Set<String> issuedJobs = new LinkedHashSet<>();
    private boolean subscribed;
    private String worker;
    private MiningJob lastJob;
    private boolean closingAfterFlush;
    private int failedAuthorizations;
    private long rateWindow;
    private int messagesInWindow;

    StratumSession(StratumServer server, Socket socket, String extraNonce) {
        this.server = server;
        this.socket = socket;
        this.extraNonce = extraNonce;
        extraNonceBytes = HexFormat.of().parseHex(extraNonce);
    }

    void run(ExecutorService workers, ScheduledExecutorService watchdog) {
        Future<?> writer = null;
        try {
            writer = workers.submit(() -> write(watchdog));
            var input = new BufferedInputStream(socket.getInputStream());
            while (!closed.get()) {
                String line = line(input);
                if (line == null) break;
                synchronized (this) {
                    long now = System.nanoTime();
                    if (now - rateWindow >= TimeUnit.SECONDS.toNanos(1)) { rateWindow = now; messagesInWindow = 0; }
                    if (++messagesInWindow > 100) break;
                    handle(line);
                }
            }
        } catch (IOException ignored) {
            // EOF, read timeout and connection closure end this session.
        } finally {
            close();
            if (writer != null) writer.cancel(true);
        }
    }

    private void handle(String line) {
        if (closingAfterFlush) return;
        Object id = null;
        boolean share = false;
        try {
            Map<?, ?> request;
            try { request = JSON.readValue(line, Map.class); }
            catch (RuntimeException exception) { throw new StratumException(20, "Invalid JSON request"); }
            if (request == null || !(request.get("method") instanceof String method)) throw new StratumException(20, "Invalid request");
            id = request.get("id");
            if (id != null && !(id instanceof Number) && !(id instanceof String)) { id = null; throw new StratumException(20, "Invalid request id"); }
            if (!(request.get("params") instanceof List<?> params)) throw new StratumException(20, "Expected parameter array");
            share = method.equals("mining.submit");
            Object result = switch (method) {
                case "mining.subscribe" -> subscribe(params);
                case "mining.authorize" -> authorize(params);
                case "mining.submit" -> submit(params);
                case "mining.configure" -> configure(params);
                case "mining.extranonce.subscribe" -> true;
                case "mining.suggest_difficulty" -> false;
                default -> throw new StratumException(20, "Unsupported method");
            };
            response(id, result, null);
            if (failedAuthorizations >= 3) close();
            var job = server.current();
            if (job != null) publish(job);
        } catch (StratumException exception) {
            if (share) server.rejected();
            response(id, null, Arrays.asList(exception.code(), exception.getMessage(), null));
        } catch (IllegalArgumentException exception) {
            if (share) server.rejected();
            response(id, null, Arrays.asList(20, "Invalid parameters", null));
        } catch (RuntimeException exception) {
            if (share) server.rejected();
            System.getLogger(StratumSession.class.getName()).log(System.Logger.Level.ERROR, "Stratum request failed", exception);
            response(id, null, Arrays.asList(20, "Internal error", null));
        }
    }

    private Object subscribe(List<?> params) {
        if (subscribed || params.size() > 2) throw new StratumException(20, "Invalid or repeated subscription");
        subscribed = true;
        return List.of(List.of(List.of("mining.set_difficulty", extraNonce), List.of("mining.notify", extraNonce)),
                extraNonce, ExtraNonceManager.EXTRANONCE2_SIZE);
    }

    private boolean authorize(List<?> params) {
        if (params.size() != 2) throw new StratumException(20, "Expected worker and password");
        String name = string(params, 0);
        if (worker != null && !worker.equals(name)) throw new StratumException(20, "Worker cannot change within a session");
        if (!server.authorize(name, string(params, 1))) { failedAuthorizations++; return false; }
        worker = name;
        return true;
    }

    private Object configure(List<?> params) {
        if (params.size() != 2 || !(params.getFirst() instanceof List<?> extensions) || !(params.get(1) instanceof Map<?, ?>))
            throw new StratumException(20, "Invalid extension negotiation");
        Map<String, Boolean> result = new LinkedHashMap<>();
        for (Object extension : extensions) {
            if (!(extension instanceof String name)) throw new StratumException(20, "Invalid extension name");
            result.put(name, false); // Explicitly decline version rolling and other unimplemented extensions.
        }
        return result;
    }

    private boolean submit(List<?> params) {
        if (!subscribed) throw new StratumException(25, "Not subscribed");
        if (worker == null) throw new StratumException(24, "Unauthorized worker");
        if (params.size() != 5) throw new StratumException(20, "Expected worker, job, extranonce2, ntime, nonce");
        if (!worker.equals(string(params, 0))) throw new StratumException(24, "Unauthorized worker");
        String id = string(params, 1);
        if (!issuedJobs.contains(id)) throw new StratumException(21, "Job not found");
        var job = server.jobs().find(id).orElseThrow(() -> new StratumException(21, "Stale job"));
        if (!server.backend().isCurrent(job.work().block().header().previousBlockHash())) throw new StratumException(21, "Stale job");
        byte[] extraNonce2 = hex(string(params, 2), ExtraNonceManager.EXTRANONCE2_SIZE);
        long time = uint32(string(params, 3));
        long nonce = uint32(string(params, 4));
        var candidate = job.candidate(extraNonceBytes, extraNonce2, time, nonce);
        var hash = candidate.header().hash();
        if (seenShares.contains(hash)) throw new StratumException(22, "Duplicate share");
        boolean isBlock = server.validator().validate(job, candidate, server.backend().currentTimeSeconds());
        if (seenShares.size() >= 8192) { close(); throw new StratumException(20, "Session share limit reached; reconnect"); }
        // Record only accepted shares. An unexpected storage error leaves the solution retryable.
        if (isBlock && !server.backend().submit(job.block(candidate))) throw new StratumException(21, "Block no longer extends the active tip");
        seenShares.add(hash);
        server.accepted(isBlock);
        return true;
    }

    synchronized void publish(MiningJob job) {
        if (closed.get() || closingAfterFlush || !subscribed || worker == null || (lastJob != null && lastJob.id().equals(job.id()))) return;
        boolean clean = lastJob == null || !lastJob.work().block().header().previousBlockHash().equals(job.work().block().header().previousBlockHash());
        if (lastJob == null) notification("mining.set_difficulty", List.of(server.difficulty()));
        if (clean) { issuedJobs.clear(); seenShares.clear(); }
        issuedJobs.add(job.id());
        while (issuedJobs.size() > 8) issuedJobs.remove(issuedJobs.iterator().next());
        lastJob = job;
        notification("mining.notify", job.notification(clean));
    }

    synchronized void invalidate() {
        if (lastJob != null && !closed.get() && !closingAfterFlush) {
            // Flush an already accepted block's response before disconnecting the miner.
            closingAfterFlush = true;
            if (!outgoing.offer("")) close();
        }
    }

    private void response(Object id, Object result, Object error) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", id); value.put("result", result); value.put("error", error);
        send(value);
    }

    private void notification(String method, List<?> params) {
        var value = new LinkedHashMap<String, Object>();
        value.put("id", null); value.put("method", method); value.put("params", params);
        send(value);
    }

    private void send(Object value) {
        if (!closed.get() && !outgoing.offer(JSON.writeValueAsString(value) + "\n")) close();
    }

    private void write(ScheduledExecutorService watchdog) {
        try {
            var output = new BufferedOutputStream(socket.getOutputStream());
            while (!closed.get()) {
                String message = outgoing.take();
                if (message.isEmpty()) break;
                var timeout = watchdog.schedule(this::close, 10, TimeUnit.SECONDS);
                try { output.write(message.getBytes(StandardCharsets.UTF_8)); output.flush(); }
                finally { timeout.cancel(false); }
            }
        } catch (IOException | RejectedExecutionException ignored) {
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        finally { close(); }
    }

    private static String line(InputStream input) throws IOException {
        var line = new ByteArrayOutputStream();
        for (int value; (value = input.read()) != -1;) {
            if (value == '\n') return line.toString(StandardCharsets.UTF_8);
            if (line.size() >= 16_384) throw new IOException("Stratum request exceeds line limit");
            line.write(value);
        }
        return null;
    }

    private static String string(List<?> values, int index) {
        if (!(values.get(index) instanceof String value)) throw new StratumException(20, "Expected string parameter");
        return value;
    }
    private static byte[] hex(String value, int bytes) {
        if (value.length() != bytes * 2) throw new StratumException(20, "Invalid hex length");
        return HexFormat.of().parseHex(value);
    }
    private static long uint32(String value) { hex(value, 4); return Long.parseUnsignedLong(value, 16); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
