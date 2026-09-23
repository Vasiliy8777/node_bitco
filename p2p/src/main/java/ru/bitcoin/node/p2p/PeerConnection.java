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
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Optional;

public final class PeerConnection implements AutoCloseable {

    public static final int DEFAULT_CONNECT_TIMEOUT_MILLIS =
            10_000;

    public static final int DEFAULT_READ_TIMEOUT_MILLIS =
            30_000;

    private final NetworkParameters networkParameters;
    private final int connectTimeoutMillis;
    private final int readTimeoutMillis;

    private volatile Socket socket;
    private InputStream input;
    private OutputStream output;

    private BitcoinMessageEncoder encoder;
    private BitcoinMessageStreamReader reader;

    private final Object sendLock =
            new Object();

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

        synchronized (sendLock) {
            ensureConnected();

            byte[] packet =
                    encoder.encode(message);

            output.write(packet);
            output.flush();
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
        synchronized (sendLock) {
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
        }
    }
}
