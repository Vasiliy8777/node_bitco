package ru.bitcoin.node.rvn.app.sync;

import org.springframework.boot.json.JacksonJsonParser;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Bounded system DNS with optional HTTPS A/AAAA fallback. Addresses only
 * discover untrusted peers; all downloaded headers/bodies still need consensus checks. */
public final class RvnSeedResolver implements AutoCloseable {
    @FunctionalInterface interface Lookup { List<String> resolve(String host) throws IOException; }
    private final Lookup system, fallback;
    private final ExecutorService dns;
    private final HttpClient http;
    private volatile boolean closed;
    private final Map<String, Cached> cache = new HashMap<>();
    private record Cached(List<String> addresses, long until) {}

    public RvnSeedResolver(String httpsEndpoint) {
        URI endpoint = httpsEndpoint == null || httpsEndpoint.isBlank() ? null : URI.create(httpsEndpoint);
        if (endpoint != null && (!"https".equals(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getRawQuery() != null || endpoint.getFragment() != null || endpoint.getUserInfo() != null))
            throw new IllegalArgumentException("RVN DNS-over-HTTPS endpoint must be an HTTPS URL without query/credentials");
        dns = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
                Thread.ofPlatform().daemon(true).name("rvn-dns").factory(), new ThreadPoolExecutor.AbortPolicy());
        http = endpoint == null ? null : HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build();
        system = this::system;
        fallback = endpoint == null ? null : host -> https(endpoint, host);
    }
    RvnSeedResolver(Lookup system, Lookup fallback) {
        this.system = system; this.fallback = fallback; dns = null; http = null;
    }
    public synchronized List<String> resolve(String host) throws IOException {
        if (closed) throw new IOException("RVN seed resolver is closed");
        if (host == null || host.isBlank() || host.length() > 253) throw new IOException("Invalid RVN peer hostname");
        if (ipv4(host)) return List.of(host);
        // IPv6 literals are resolved locally, without querying a DNS seed.
        if (host.indexOf(':') >= 0) return List.of(InetAddress.getByName(host).getHostAddress());
        var cached = cache.get(host);
        if (cached != null && cached.until() > System.nanoTime()) return cached.addresses();
        List<String> addresses;
        try { addresses = system.resolve(host); }
        catch (IOException failed) {
            if (Thread.currentThread().isInterrupted()) throw failed;
            if (fallback == null) throw failed;
            try { addresses = fallback.resolve(host); }
            catch (IOException second) { second.addSuppressed(failed); throw second; }
        }
        var unique = new ArrayList<>(new LinkedHashSet<>(addresses));
        if (unique.isEmpty() || unique.size() > 256) throw new IOException("RVN DNS returned no usable addresses or exceeded limit");
        Collections.shuffle(unique);
        var result = interleave(unique);
        if (cache.size() >= 64) cache.clear();
        cache.put(host, new Cached(result, System.nanoTime() + TimeUnit.SECONDS.toNanos(30)));
        return result;
    }
    private List<String> system(String host) throws IOException {
        Future<InetAddress[]> pending;
        try { pending = dns.submit(() -> InetAddress.getAllByName(host)); }
        catch (RejectedExecutionException e) { throw new IOException("RVN system DNS is still busy", e); }
        try { return Arrays.stream(pending.get(1500, TimeUnit.MILLISECONDS)).map(InetAddress::getHostAddress).toList(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("RVN DNS interrupted"); }
        catch (ExecutionException | TimeoutException e) { throw new IOException("RVN system DNS failed for " + host, e); }
        finally { pending.cancel(true); }
    }
    private List<String> https(URI endpoint, String host) throws IOException {
        var addresses = new LinkedHashSet<String>();
        IOException failure = null;
        for (int type : new int[] {28, 1}) {
            try { addresses.addAll(https(endpoint, host, type)); }
            catch (IOException e) {
                if (Thread.currentThread().isInterrupted()) throw e;
                if (failure == null) failure = e; else failure.addSuppressed(e);
            }
        }
        if (addresses.isEmpty()) {
            if (failure != null) throw failure;
            throw new IOException("RVN HTTPS DNS has no A/AAAA records");
        }
        return List.copyOf(addresses);
    }
    private List<String> https(URI endpoint, String host, int type) throws IOException {
        URI query = URI.create(endpoint + "?name=" + URLEncoder.encode(host, StandardCharsets.UTF_8)
                + "&type=" + type + "&edns_client_subnet=0.0.0.0%2F0");
        var request = HttpRequest.newBuilder(query).timeout(Duration.ofSeconds(5)).header("Accept", "application/dns-json").GET().build();
        try {
            var response = http.send(request, info -> new LimitedBody());
            if (response.statusCode() != 200) throw new IOException("RVN HTTPS DNS HTTP status " + response.statusCode());
            return parse(host, new String(response.body(), StandardCharsets.UTF_8), type);
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException("RVN HTTPS DNS interrupted"); }
    }
    static List<String> parse(String host, String json) throws IOException {
        return parse(host, json, 1);
    }
    static List<String> parse(String host, String json, int recordType) throws IOException {
        if (recordType != 1 && recordType != 28) throw new IllegalArgumentException("DNS record type");
        if (json.length() > 65536) throw new IOException("RVN HTTPS DNS response size limit");
        try {
            var value = new JacksonJsonParser().parseMap(json);
            if (!(value.get("Status") instanceof Number status) || status.intValue() != 0 || !Boolean.FALSE.equals(value.get("TC")))
                throw new IOException("RVN HTTPS DNS failed or truncated");
            if (!(value.get("Question") instanceof List<?> questions) || questions.size() != 1
                    || !(questions.getFirst() instanceof Map<?, ?> q) || !(q.get("type") instanceof Number type) || type.intValue() != recordType
                    || !(q.get("name") instanceof String name) || !name.replaceFirst("\\.$", "").equalsIgnoreCase(host.replaceFirst("\\.$", "")))
                throw new IOException("RVN HTTPS DNS question mismatch");
            Object rawAnswers = value.getOrDefault("Answer", List.of());
            if (!(rawAnswers instanceof List<?> answers) || answers.size() > 256) throw new IOException("RVN HTTPS DNS answers oversized/invalid");
            var result = new LinkedHashSet<String>();
            for (var raw : answers) if (raw instanceof Map<?, ?> answer && answer.get("type") instanceof Number t && t.intValue() == recordType) {
                if (!(answer.get("data") instanceof String ip) || !(recordType == 1 ? ipv4(ip) : ipv6(ip)))
                    throw new IOException("Invalid RVN HTTPS DNS address record");
                result.add(ip);
            }
            return List.copyOf(result);
        } catch (RuntimeException e) { throw new IOException("Malformed RVN HTTPS DNS JSON", e); }
    }
    private static boolean ipv6(String ip) {
        // Restrict to literals before using InetAddress: never resolve DNS data as a hostname.
        if (ip.indexOf(':') < 0 || !ip.matches("[0-9a-fA-F:.]+")) return false;
        try { InetAddress.getByName(ip); return true; }
        catch (UnknownHostException e) { return false; }
    }
    static List<String> interleave(List<String> addresses) {
        var v6 = addresses.stream().filter(a -> a.indexOf(':') >= 0).toList();
        var v4 = addresses.stream().filter(a -> a.indexOf(':') < 0).toList();
        var result = new ArrayList<String>();
        for (int i = 0; i < Math.max(v6.size(), v4.size()); i++) {
            if (i < v6.size()) result.add(v6.get(i));
            if (i < v4.size()) result.add(v4.get(i));
        }
        return List.copyOf(result);
    }
    private static boolean ipv4(String ip) {
        if (!ip.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) return false;
        for (String part : ip.split("\\.")) if (Integer.parseInt(part) > 255 || part.length() > 1 && part.charAt(0) == '0') return false;
        return true;
    }
    /** Enforce the size limit while receiving, before a body can accumulate. */
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        public CompletionStage<byte[]> getBody() { return result; }
        public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        public void onNext(List<java.nio.ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > 65536 - bytes.size()) {
                    subscription.cancel(); result.completeExceptionally(new IOException("RVN HTTPS DNS response size limit")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        public void onError(Throwable error) { result.completeExceptionally(error); }
        public void onComplete() { result.complete(bytes.toByteArray()); }
    }
    @Override public void close() { closed = true; if (dns != null) dns.shutdownNow(); if (http != null) http.shutdownNow(); }
}
