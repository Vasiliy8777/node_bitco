package ru.bitcoin.node.rvn.p2p;

import ru.bitcoin.node.rvn.protocol.*;
import ru.bitcoin.node.rvn.protocol.RvnNetwork;
import java.io.*;
import java.net.*;

/**
 * Single outbound handshake probe. Does not claim to be a synchronized or validating node.
 */
public final class RvnPeerProbe {
    public record Result(String host, int port, int peerVersion, boolean verack) {
    }

    public Result probe(RvnNetwork network, String host, int port, int timeoutMillis) throws IOException {
        if (timeoutMillis < 100 || timeoutMillis > 60000) throw new IllegalArgumentException("timeout");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            RvnWire.write(out, network, new RvnMessage("version", RvnVersion.outbound(70028, port, 0)));
            boolean version = false, verack = false;
            int peerVersion = 0;
            for (int i = 0; i < 16 && !(version && verack); i++) {
                RvnMessage msg = RvnWire.read(in, network);
                switch (msg.command()) {
                    case "version" -> {
                        if (version) throw new IOException("Duplicate version");
                        byte[] body = msg.payload();
                        if (body.length < 4) throw new IOException("Truncated version");
                        peerVersion = (int) ((body[0] & 255) | ((body[1] & 255) << 8) | ((body[2] & 255) << 16) | ((body[3] & 255) << 24));
                        version = true;
                        RvnWire.write(out, network, new RvnMessage("verack", new byte[0]));
                    }
                    case "verack" -> {
                        if (!version) throw new IOException("verack before version");
                        verack = true;
                    }
                    case "ping" -> {
                        if (msg.payload().length == 8)
                            RvnWire.write(out, network, new RvnMessage("pong", msg.payload()));
                    }
                    default -> {
                    }
                }
            }
            if (!version || !verack) throw new IOException("Handshake incomplete");
            return new Result(host, port, peerVersion, true);
        }
    }
}
