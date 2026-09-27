package ru.bitcoin.node.p2p.transport;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.p2p.message.BitcoinMessage;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.io.*;
import java.security.SecureRandom;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class Bip324TransportSessionTest {
    @Test
    void initiatorAndResponderCompleteHandshakeAndExchangeMessages() throws Exception {
        var aToB = new PipedOutputStream();
        var bIn = new PipedInputStream(aToB, 32768);
        var bToA = new PipedOutputStream();
        var aIn = new PipedInputStream(bToA, 32768);
        var network = NetworkParametersRegistry.regtest();
        var responder = CompletableFuture.supplyAsync(() -> {
            try {
                byte[] prefix = bIn.readNBytes(1);
                return Bip324TransportSession.respond(bIn, bToA, network, seeded(2), prefix);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        });
        var initiator = Bip324TransportSession.initiate(aIn, aToB, network, seeded(1));
        var response = responder.get(5, TimeUnit.SECONDS);
        assertArrayEquals(initiator.sessionId(), response.sessionId());
        initiator.send(aToB, new BitcoinMessage("ping", new byte[]{1, 2, 3}));
        var ping = response.receive(bIn);
        assertEquals("ping", ping.command());
        assertArrayEquals(new byte[]{1, 2, 3}, ping.payload());
        response.send(bToA, new BitcoinMessage("pong", ping.payload()));
        assertEquals("pong", initiator.receive(aIn).command());
    }

    @Test
    void exposesExactV1DiscriminatorPrefix() {
        byte[] prefix = Bip324TransportSession.v1Prefix(NetworkParametersRegistry.mainnet());
        assertEquals(16, prefix.length);
        assertArrayEquals(new byte[]{(byte) 0xf9, (byte) 0xbe, (byte) 0xb4, (byte) 0xd9}, java.util.Arrays.copyOf(prefix, 4));
        assertEquals("version", new String(prefix, 4, 7, java.nio.charset.StandardCharsets.US_ASCII));
    }

    private static SecureRandom seeded(int value) {
        try {
            var r = SecureRandom.getInstance("SHA1PRNG");
            r.setSeed(new byte[]{(byte) value});
            return r;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
