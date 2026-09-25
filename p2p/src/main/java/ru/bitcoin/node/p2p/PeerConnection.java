package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.codec.BitcoinMessageDecoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageEncoder;
import ru.bitcoin.node.p2p.codec.BitcoinMessageStreamReader;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.protocol.network.NetworkParameters;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Optional;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

public final class PeerConnection implements AutoCloseable {

    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS =
            10_000;

    public static final int DEFAULT_READ_TIMEOUT_MILLIS =
            30_000;

    public static final int DEFAULT_WRITE_TIMEOUT_MILLIS = 10_000;
    private static final ScheduledThreadPoolExecutor WRITE_DEADLINES = createWriteDeadlines();

    private static ScheduledThreadPoolExecutor createWriteDeadlines() {
        var executor = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "bitcoin-socket-write-deadline");
            thread.setDaemon(true);
            return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    private final NetworkParameters networkParameters;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;
    private final long writeTimeoutNanos;
    private final PeerWriteBudget.Account writeBudget;

    private volatile Socket socket;
    private InputStream input;
    private OutputStream output;

    private BitcoinMessageEncoder encoder;
    private BitcoinMessageStreamReader reader;

    private final ReentrantLock sendLock = new ReentrantLock(true);
    private final Object asyncLock = new Object();
    private ThreadPoolExecutor asyncWriter;

    public PeerConnection(
            NetworkParameters networkParameters
    ) {
        this(
                networkParameters,
                DEFAULT_CONNECT_TIMEOUT_MILLIS,
                DEFAULT_READ_TIMEOUT_MILLIS
        );
    }

    public PeerConnection(
            NetworkParameters networkParameters,
            int connectTimeoutMillis,
            int readTimeoutMillis
    ) {
        this(networkParameters, connectTimeoutMillis, readTimeoutMillis, DEFAULT_WRITE_TIMEOUT_MILLIS);
    }

    public PeerConnection(
            NetworkParameters networkParameters,
            int connectTimeoutMillis,
            int readTimeoutMillis,
            int writeTimeoutMillis
    ) {
        this(networkParameters, connectTimeoutMillis, readTimeoutMillis, writeTimeoutMillis,
                PeerWriteBudget.SHARED);
    }

    PeerConnection(NetworkParameters networkParameters, int connectTimeoutMillis,
                   int readTimeoutMillis, int writeTimeoutMillis, PeerWriteBudget budget) {
        if (networkParameters == null) {
            throw new IllegalArgumentException(
                    "networkParameters must not be null"
            );
        }

        if (connectTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "connectTimeoutMillis must be positive"
            );
        }

        if (readTimeoutMillis <= 0) {
            throw new IllegalArgumentException(
                    "readTimeoutMillis must be positive"
            );
        }

        if (writeTimeoutMillis <= 0) {
            throw new IllegalArgumentException("writeTimeoutMillis must be positive");
        }
        this.writeTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(writeTimeoutMillis);
        this.writeBudget = java.util.Objects.requireNonNull(budget, "budget").newAccount();

        this.networkParameters =
                networkParameters;

        this.connectTimeoutMillis =
                connectTimeoutMillis;

        this.readTimeoutMillis =
                readTimeoutMillis;
    }

    public void connect(
            String host,
            int port
    ) throws IOException {

        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException(
                    "host must not be blank"
            );
        }

        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException(
                    "Invalid port: " + port
            );
        }

        if (isConnected()) {
            throw new IllegalStateException(
                    "Peer connection is already open"
            );
        }

        Socket newSocket =
                new Socket();

        try {
            newSocket.connect(
                    new InetSocketAddress(
                            host,
                            port
                    ),
                    connectTimeoutMillis
            );

            newSocket.setSoTimeout(
                    readTimeoutMillis
            );

            newSocket.setTcpNoDelay(true);

            InputStream newInput =
                    new BufferedInputStream(
                            newSocket.getInputStream()
                    );

            OutputStream newOutput =
                    new BufferedOutputStream(
                            newSocket.getOutputStream()
                    );

            BitcoinMessageEncoder newEncoder =
                    new BitcoinMessageEncoder(
                            networkParameters
                    );

            BitcoinMessageStreamReader newReader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    networkParameters
                            )
                    );

            socket = newSocket;
            input = newInput;
            output = newOutput;
            encoder = newEncoder;
            reader = newReader;

        } catch (IOException | RuntimeException e) {
            try {
                newSocket.close();
            } catch (IOException ignored) {
                // Preserve the original exception.
            }

            throw e;
        }
    }

    public void accept(
            Socket acceptedSocket
    ) throws IOException {

        if (acceptedSocket == null) {
            throw new IllegalArgumentException(
                    "acceptedSocket must not be null"
            );
        }

        if (!acceptedSocket.isConnected()
                || acceptedSocket.isClosed()) {
            throw new IllegalArgumentException(
                    "acceptedSocket must be connected and open"
            );
        }

        if (isConnected()) {
            throw new IllegalStateException(
                    "Peer connection is already open"
            );
        }

        try {
            acceptedSocket.setSoTimeout(
                    readTimeoutMillis
            );

            acceptedSocket.setTcpNoDelay(
                    true
            );

            InputStream newInput =
                    new BufferedInputStream(
                            acceptedSocket.getInputStream()
                    );

            OutputStream newOutput =
                    new BufferedOutputStream(
                            acceptedSocket.getOutputStream()
                    );

            BitcoinMessageEncoder newEncoder =
                    new BitcoinMessageEncoder(
                            networkParameters
                    );

            BitcoinMessageStreamReader newReader =
                    new BitcoinMessageStreamReader(
                            new BitcoinMessageDecoder(
                                    networkParameters
                            )
                    );

            socket =
                    acceptedSocket;

            input =
                    newInput;

            output =
                    newOutput;

            encoder =
                    newEncoder;

            reader =
                    newReader;

        } catch (IOException | RuntimeException exception) {

            try {
                acceptedSocket.close();
            } catch (IOException ignored) {
                // Preserve the original exception.
            }

            throw exception;
        }
    }

    public void send(
            BitcoinMessage message
    ) throws IOException {

        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        ensureConnected();
        try (var reservation = writeBudget.reserve(message.payloadLength())) {
            writeMessage(message, System.nanoTime());
        }
    }

    /** Enqueues a bounded FIFO response; failures after admission go to the owner. */
    public void sendAsync(BitcoinMessage message, Consumer<IOException> onFailure) throws IOException {
        java.util.Objects.requireNonNull(message, "message");
        java.util.Objects.requireNonNull(onFailure, "onFailure");
        synchronized (asyncLock) {
            ensureConnected();
            var reservation = writeBudget.reserve(message.payloadLength());
            try {
                if (asyncWriter == null) {
                    asyncWriter = new ThreadPoolExecutor(1, 1, 5, TimeUnit.SECONDS,
                            new ArrayBlockingQueue<>(63), task -> {
                                Thread thread = new Thread(task, "bitcoin-peer-response");
                                thread.setDaemon(true);
                                return thread;
                            });
                    asyncWriter.allowCoreThreadTimeOut(true);
                }
                asyncWriter.execute(new AsyncWrite(message, reservation, onFailure));
            } catch (RuntimeException | Error failure) {
                reservation.close();
                if (failure instanceof RejectedExecutionException) {
                    throw new IOException("Peer response queue full or closed", failure);
                }
                throw failure;
            }
        }
    }

    private final class AsyncWrite implements Runnable {
        private final BitcoinMessage message;
        private final PeerWriteBudget.Reservation reservation;
        private final Consumer<IOException> onFailure;
        private final long submitted = System.nanoTime();

        private AsyncWrite(BitcoinMessage message, PeerWriteBudget.Reservation reservation,
                           Consumer<IOException> onFailure) {
            this.message = message;
            this.reservation = reservation;
            this.onFailure = onFailure;
        }

        @Override public void run() {
            IOException failure = null;
            try {
                writeMessage(message, submitted);
            } catch (IOException exception) {
                failure = exception;
            } catch (RuntimeException exception) {
                failure = new IOException("Peer asynchronous write failed", exception);
            } finally {
                reservation.close();
            }
            if (failure != null) onFailure.accept(failure);
        }
    }

    private void writeMessage(BitcoinMessage message, long started) throws IOException {
        long lockWait = writeTimeoutNanos - (System.nanoTime() - started);
        if (lockWait <= 0) throw new SocketTimeoutException("Peer response queue timeout");
        try {
            if (!sendLock.tryLock(lockWait, TimeUnit.NANOSECONDS)) {
                throw new SocketTimeoutException("Peer write lock timeout");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            var failure = new InterruptedIOException("Interrupted waiting for peer write lock");
            failure.initCause(interrupted);
            throw failure;
        }
        try {
            ensureConnected();
            long remaining = writeTimeoutNanos - (System.nanoTime() - started);
            if (remaining <= 0) throw new SocketTimeoutException("Peer write lock timeout");
            // Capture only this socket; an old deadline cannot close a later connection.
            Socket writingSocket = socket;
            AtomicBoolean finished = new AtomicBoolean();
            var deadline = WRITE_DEADLINES.schedule(() -> {
                if (finished.compareAndSet(false, true)) {
                    // Closing without sendLock releases a blocked socket writer.
                    try { writingSocket.close(); }
                    catch (IOException ignored) { }
                }
            }, remaining, TimeUnit.NANOSECONDS);
            try {
                byte[] packet = encoder.encode(message);
                output.write(packet);
                output.flush();
                if (!finished.compareAndSet(false, true)) {
                    throw new SocketTimeoutException("Peer socket write timeout");
                }
            } finally {
                finished.set(true);
                deadline.cancel(false);
            }
        } finally {
            sendLock.unlock();
        }
    }

    public Optional<BitcoinMessage> receive()
            throws IOException {

        ensureConnected();

        return reader.read(input);
    }

    void disableReadTimeout()
            throws IOException {

        ensureConnected();

        socket.setSoTimeout(
                0
        );
    }

    public boolean isConnected() {
        return socket != null
                && socket.isConnected()
                && !socket.isClosed();
    }

    public InetSocketAddress remoteAddress() {
        ensureConnected();

        return (InetSocketAddress)
                socket.getRemoteSocketAddress();
    }

    public InetSocketAddress localAddress() {
        ensureConnected();

        return (InetSocketAddress)
                socket.getLocalSocketAddress();
    }

    private void ensureConnected() {
        if (!isConnected()) {
            throw new IllegalStateException(
                    "Peer connection is not open"
            );
        }
    }

    @Override
    public void close() throws IOException {
        // Closing the socket interrupts a blocked writer; waiting for sendLock first can deadlock shutdown.
        Socket closingSocket = socket;
        if (closingSocket != null) closingSocket.close();
        synchronized (asyncLock) {
            if (asyncWriter != null) {
                for (Runnable pending : asyncWriter.shutdownNow()) {
                    ((AsyncWrite) pending).reservation.close();
                }
                asyncWriter = null;
            }
        }
        sendLock.lock();
        try {
            Socket currentSocket =
                    socket;

            socket = null;
            input = null;
            output = null;
            encoder = null;
            reader = null;

            if (currentSocket != null) {
                currentSocket.close();
            }
        } finally {
            sendLock.unlock();
        }
    }
}
