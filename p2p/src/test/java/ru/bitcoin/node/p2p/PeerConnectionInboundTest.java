package ru.bitcoin.node.p2p;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerConnectionInboundTest {

    @Test
    void shouldAdoptAcceptedSocket()
            throws Exception {

        NetworkParameters parameters =
                NetworkParametersRegistry.regtest();

        try (ServerSocket serverSocket =
                     new ServerSocket(0);
             Socket client =
                     new Socket(
                             "127.0.0.1",
                             serverSocket.getLocalPort()
                     );
             Socket accepted =
                     serverSocket.accept();
             PeerConnection connection =
                     new PeerConnection(
                             parameters
                     )) {

            connection.accept(
                    accepted
            );

            assertTrue(
                    connection.isConnected()
            );

            assertEquals(
                    client.getLocalPort(),
                    connection.remoteAddress().getPort()
            );

            assertEquals(
                    serverSocket.getLocalPort(),
                    connection.localAddress().getPort()
            );
        }
    }

    @Test
    void shouldRejectNullAcceptedSocket() {

        PeerConnection connection =
                new PeerConnection(
                        NetworkParametersRegistry.regtest()
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> connection.accept(null)
        );
    }

    @Test
    void shouldRejectClosedAcceptedSocket()
            throws Exception {

        Socket socket =
                new Socket();

        socket.close();

        try (PeerConnection connection =
                     new PeerConnection(
                             NetworkParametersRegistry.regtest()
                     )) {

            assertThrows(
                    IllegalArgumentException.class,
                    () -> connection.accept(socket)
            );
        }
    }

    @Test
    void shouldRejectSecondSocket()
            throws Exception {

        try (ServerSocket firstServer =
                     new ServerSocket(0);
             Socket firstClient =
                     new Socket(
                             "127.0.0.1",
                             firstServer.getLocalPort()
                     );
             Socket firstAccepted =
                     firstServer.accept();
             PeerConnection connection =
                     new PeerConnection(
                             NetworkParametersRegistry.regtest()
                     )) {

            connection.accept(
                    firstAccepted
            );

            try (ServerSocket secondServer =
                         new ServerSocket(0);
                 Socket secondClient =
                         new Socket(
                                 "127.0.0.1",
                                 secondServer.getLocalPort()
                         );
                 Socket secondAccepted =
                         secondServer.accept()) {

                assertThrows(
                        IllegalStateException.class,
                        () -> connection.accept(
                                secondAccepted
                        )
                );
            }
        }
    }
}