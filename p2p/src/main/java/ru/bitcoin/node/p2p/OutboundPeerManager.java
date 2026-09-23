package ru.bitcoin.node.p2p;

import ru.bitcoin.node.p2p.address.OutboundPeerSelector;
import ru.bitcoin.node.p2p.address.PeerAddress;
import ru.bitcoin.node.p2p.address.PeerAddressManager;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

public final class OutboundPeerManager {

    private final BitcoinClient bitcoinClient;
    private final PeerManager peerManager;
    private final PeerAddressManager addressManager;
    private final Supplier<Instant> clock;

    public OutboundPeerManager(
            BitcoinClient bitcoinClient,
            PeerManager peerManager,
            PeerAddressManager addressManager
    ) {
        this(
                bitcoinClient,
                peerManager,
                addressManager,
                new OutboundPeerSelector(
                        addressManager
                ),
                Instant::now
        );
    }

    OutboundPeerManager(
            BitcoinClient bitcoinClient,
            PeerManager peerManager,
            PeerAddressManager addressManager,
            OutboundPeerSelector selector,
            Supplier<Instant> clock
    ) {
        this.bitcoinClient =
                Objects.requireNonNull(
                        bitcoinClient,
                        "bitcoinClient"
                );

        this.peerManager =
                Objects.requireNonNull(
                        peerManager,
                        "peerManager"
                );

        this.addressManager =
                Objects.requireNonNull(
                        addressManager,
                        "addressManager"
                );

        this.clock =
                Objects.requireNonNull(
                        clock,
                        "clock"
                );
    }

    public Peer connectOne(
            int startHeight
    ) throws IOException {

        return connectOneWithAddress(
                startHeight,
                List.of()
        ).peer();
    }

    public Peer connectOne(
            int startHeight,
            List<PeerAddress> excludedAddresses
    ) throws IOException {

        return connectOneWithAddress(
                startHeight,
                excludedAddresses
        ).peer();
    }

    public OutboundPeerConnection connectOneWithAddress(
            int startHeight,
            List<PeerAddress> excludedAddresses
    ) throws IOException {

        if (startHeight < 0) {

            throw new IllegalArgumentException(
                    "startHeight must not be negative"
            );
        }

        Objects.requireNonNull(
                excludedAddresses,
                "excludedAddresses"
        );

        java.util.Set<PeerAddress> attempted =
                new java.util.LinkedHashSet<>(
                        excludedAddresses
                );

        IOException failure =
                new IOException(
                        "Unable to connect to any known peer address"
                );

        while (true) {

            PeerAddress address =
                    addressManager.select(
                                    attempted
                            )
                            .orElse(
                                    null
                            );

            if (address == null) {

                if (attempted.size()
                        == excludedAddresses.size()) {

                    throw new IOException(
                            "No known peer addresses available"
                    );
                }

                throw failure;
            }

            attempted.add(
                    address
            );

            addressManager.markAttempt(
                    address,
                    now()
            );

            try {

                Peer peer =
                        bitcoinClient.connect(
                                address.hostAddress(),
                                address.port(),
                                startHeight
                        );

                if (!peer.isReady()) {

                    try {

                        peer.close();

                    } catch (IOException closeException) {

                        failure.addSuppressed(
                                closeException
                        );
                    }

                    failure.addSuppressed(
                            new IOException(
                                    "BitcoinClient returned non-ready peer "
                                            + address.hostAddress()
                                            + ":"
                                            + address.port()
                            )
                    );

                    continue;
                }

                addressManager.markSuccess(
                        address,
                        now()
                );

                try {

                    peerManager.add(
                            peer
                    );

                } catch (RuntimeException exception) {

                    try {

                        peer.close();

                    } catch (IOException closeException) {

                        exception.addSuppressed(
                                closeException
                        );
                    }

                    throw exception;
                }

                return new OutboundPeerConnection(
                        peer,
                        address
                );

            } catch (IOException exception) {

                failure.addSuppressed(
                        exception
                );
            }
        }
    }

    private Instant now() {

        return Objects.requireNonNull(
                clock.get(),
                "clock returned null"
        );
    }
}