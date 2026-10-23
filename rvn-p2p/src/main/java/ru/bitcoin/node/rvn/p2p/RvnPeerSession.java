package ru.bitcoin.node.rvn.p2p;

import ru.bitcoin.node.rvn.protocol.RvnMessage;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import ru.bitcoin.node.rvn.protocol.RvnVersion;
import ru.bitcoin.node.rvn.protocol.RvnWire;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One bounded outbound RVN peer session. This is transport infrastructure, not block validation.
 * The session owns its socket and reader thread; callers MUST close it.
 */
public final class RvnPeerSession implements AutoCloseable {
    public static final int DEFAULT_QUEUE_CAPACITY = 64;
    private final RvnNetwork network;
    private final Socket socket;
    private final BlockingQueue<RvnMessage> incoming;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<IOException> failure = new AtomicReference<>();
    private final Thread reader;
    private final int peerVersion;

    private RvnPeerSession(RvnNetwork network, Socket socket, int peerVersion, int capacity) {
        this.network = network;
        this.socket = socket;
        this.peerVersion = peerVersion;
        this.incoming = new ArrayBlockingQueue<>(capacity);
        this.reader = new Thread(this::readLoop, "rvn-peer-reader");
        this.reader.setDaemon(true);
        this.reader.start();
    }

    public static RvnPeerSession connect(RvnNetwork network, String host, int port,
                                         int timeoutMillis, int queueCapacity) throws IOException {
        if (network == null) throw new IllegalArgumentException("network");
        if (host == null || host.isBlank()) throw new IllegalArgumentException("host");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port");
        if (timeoutMillis < 100 || timeoutMillis > 60000) throw new IllegalArgumentException("timeoutMillis");
        if (queueCapacity < 1 || queueCapacity > 4096) throw new IllegalArgumentException("queueCapacity");
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            socket.setTcpNoDelay(true);
            RvnWire.write(socket.getOutputStream(), network,
                    new RvnMessage("version", RvnVersion.outbound(70028, port, 0)));
            long handshakeDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            boolean gotVersion = false;
            boolean gotVerack = false;
            int remoteVersion = 0;
            for (int i = 0; i < 16 && !(gotVersion && gotVerack); i++) {
                long remainingNanos = handshakeDeadline - System.nanoTime();
                if (remainingNanos <= 0) throw new SocketTimeoutException("RVN handshake deadline exceeded");
                long remainingMillis = Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                socket.setSoTimeout((int) Math.min(timeoutMillis, remainingMillis));
                RvnMessage message = RvnWire.read(socket.getInputStream(), network);
                switch (message.command()) {
                    case "version" -> {
                        if (gotVersion) throw new IOException("Duplicate version");
                        byte[] payload = message.payload();
                        if (payload.length < 4) throw new IOException("Truncated version");
                        remoteVersion = (int) ((payload[0] & 255L) | ((payload[1] & 255L) << 8)
                                | ((payload[2] & 255L) << 16) | ((payload[3] & 255L) << 24));
                        if (remoteVersion <= 0) throw new IOException("Invalid peer version");
                        gotVersion = true;
                        RvnWire.write(socket.getOutputStream(), network, new RvnMessage("verack", new byte[0]));
                    }
                    case "verack" -> {
                        if (!gotVersion || gotVerack || message.payload().length != 0)
                            throw new IOException("Invalid verack");
                        gotVerack = true;
                    }
                    case "ping" -> {
                        if (message.payload().length == 8)
                            RvnWire.write(socket.getOutputStream(), network, new RvnMessage("pong", message.payload()));
                    }
                    default -> { /* pre-handshake noncritical messages are ignored */ }
                }
            }
            if (!gotVersion || !gotVerack) throw new IOException("Handshake incomplete");
            socket.setSoTimeout(timeoutMillis);
            return new RvnPeerSession(network, socket, remoteVersion, queueCapacity);
        } catch (IOException | RuntimeException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            throw e;
        }
    }

    private void readLoop() {
        try {
            while (!closed.get()) {
                RvnMessage message;
                try {
                    message = RvnWire.read(socket.getInputStream(), network);
                } catch (SocketTimeoutException e) {
                    continue; // read timeout permits checking shutdown; not an idle disconnect policy
                }
                if ("ping".equals(message.command())) {
                    if (message.payload().length != 8) throw new IOException("Invalid ping nonce length");
                    send(new RvnMessage("pong", message.payload()));
                } else if ("version".equals(message.command()) || "verack".equals(message.command())) {
                    throw new IOException("Duplicate handshake message");
                } else if (!incoming.offer(message)) {
                    throw new IOException("Inbound peer message queue full: " + incoming.size());
                }
            }
        } catch (IOException e) {
            if (!closed.get()) failure.compareAndSet(null, e);
        } finally {
            close();
        }
    }

    public synchronized void send(RvnMessage message) throws IOException {
        if (closed.get()) throw new IOException("RVN peer session closed");
        RvnWire.write(socket.getOutputStream(), network, message);
    }

    /**
     * Returns null if no message arrived within the requested interval.
     */
    public RvnMessage poll(long timeoutMillis) throws InterruptedException, IOException {
        if (timeoutMillis < 0) throw new IllegalArgumentException("timeoutMillis");
        RvnMessage message = incoming.poll(timeoutMillis, TimeUnit.MILLISECONDS);
        if (message != null) return message;
        IOException error = failure.get();
        if (error != null) throw new IOException("RVN peer disconnected", error);
        if (closed.get()) throw new IOException("RVN peer session closed");
        return null;
    }

    /**
     * Requests headers after the completed version/verack handshake. Does not validate returned headers.
     */
    public void requestHeaders(int protocolVersion, java.util.List<byte[]> locatorHashes, byte[] stopHash)
            throws IOException {
        send(new RvnMessage("getheaders", ru.bitcoin.node.rvn.protocol.RvnGetHeaders.encode(
                protocolVersion, locatorHashes, stopHash)));
    }

    public int peerVersion() {
        return peerVersion;
    }

    public int queuedMessages() {
        return incoming.size();
    }

    public boolean isClosed() {
        return closed.get();
    }

    public IOException failure() {
        return failure.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
