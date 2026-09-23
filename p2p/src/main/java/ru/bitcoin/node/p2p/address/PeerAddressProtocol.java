package ru.bitcoin.node.p2p.address;

import ru.bitcoin.node.p2p.Peer;
import ru.bitcoin.node.p2p.PeerMessageListener;
import ru.bitcoin.node.p2p.message.*;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handles Bitcoin P2P address discovery messages:
 *
 * addr
 * addrv2
 * getaddr
 *
 * The current PeerAddress model represents IP endpoints,
 * therefore only IPv4 and IPv6 are admitted into
 * PeerAddressManager here.
 *
 * Non-IP BIP155 address families remain represented by the
 * wire layer and will be integrated when AddrMan is upgraded
 * to the full multi-network address model.
 */
public final class PeerAddressProtocol
        implements PeerMessageListener {

    public static final int MAX_GETADDR_RESPONSE =
            1_000;

    private final PeerAddressManager addressManager;

    public PeerAddressProtocol(
            PeerAddressManager addressManager
    ) {

        this.addressManager =
                Objects.requireNonNull(
                        addressManager,
                        "addressManager"
                );
    }

    @Override
    public void onMessage(
            Peer peer,
            BitcoinMessage message
    ) {

        Objects.requireNonNull(
                peer,
                "peer"
        );

        Objects.requireNonNull(
                message,
                "message"
        );

        try {

            switch (message.command()) {

                case "addr" ->
                        handleAddr(
                                message
                        );

                case "addrv2" ->
                        handleAddrV2(
                                message
                        );

                case "getaddr" ->
                        handleGetAddr(
                                peer,
                                message
                        );

                default -> {
                    // Not an address-discovery message.
                }
            }

        } catch (PeerAddressProtocolException exception) {

            throw exception;

        } catch (IllegalArgumentException exception) {

            throw new PeerAddressProtocolException(
                    "Invalid "
                            + message.command()
                            + " message",
                    exception
            );
        }
    }

    private void handleAddr(
            BitcoinMessage message
    ) {

        AddrMessage addrMessage =
                BitcoinMessages.decodeAddr(
                        message
                );

        Instant receivedAt =
                Instant.now();

        for (AddrEntry entry :
                addrMessage.addresses()) {

            PeerAddress peerAddress =
                    toPeerAddress(
                            entry
                    );

            if (peerAddress != null) {

                addressManager.add(
                        peerAddress,
                        receivedAt
                );
            }
        }
    }

    private void handleAddrV2(
            BitcoinMessage message
    ) {

        AddrV2Message addrV2Message =
                BitcoinMessages.decodeAddrV2(
                        message
                );

        Instant receivedAt =
                Instant.now();

        for (AddrV2Entry entry :
                addrV2Message.addresses()) {

            PeerAddress peerAddress =
                    toPeerAddress(
                            entry
                    );

            if (peerAddress != null) {

                addressManager.add(
                        peerAddress,
                        receivedAt
                );
            }
        }
    }

    private void handleGetAddr(
            Peer peer,
            BitcoinMessage message
    ) {

        BitcoinMessages.validateGetAddr(
                message
        );

        /*
         * Bitcoin Core only responds to GETADDR from
         * inbound connections.
         *
         * Responding on outbound connections makes
         * AddrMan fingerprinting substantially easier.
         */
        if (!peer.isInboundConnection()) {
            return;
        }

        /*
         * At most one GETADDR response is permitted
         * for the lifetime of a connection.
         */
        if (!peer.markGetAddrReceived()) {
            return;
        }

        List<KnownPeerAddress> selected =
                addressManager.getAddr();

        if (selected.isEmpty()) {
            return;
        }

        try {

            if (peer.remoteWantsAddrV2()) {

                sendAddrV2(
                        peer,
                        selected
                );

            } else {

                sendAddr(
                        peer,
                        selected
                );
            }

        } catch (IOException exception) {

            throw new PeerAddressProtocolException(
                    "Failed to send address response",
                    exception
            );
        }
    }

    private void sendAddr(
            Peer peer,
            List<KnownPeerAddress> addresses
    ) throws IOException {

        List<AddrEntry> entries =
                new ArrayList<>(
                        addresses.size()
                );

        for (KnownPeerAddress known :
                addresses) {

            PeerAddress address =
                    known.peerAddress();

            entries.add(
                    AddrEntry.fromIp(
                            timestamp(
                                    known
                            ),
                            address.services(),
                            address.address(),
                            address.port()
                    )
            );
        }

        if (entries.isEmpty()) {
            return;
        }

        peer.send(
                BitcoinMessages.addr(
                        new AddrMessage(
                                entries
                        )
                )
        );
    }

    private void sendAddrV2(
            Peer peer,
            List<KnownPeerAddress> addresses
    ) throws IOException {

        List<AddrV2Entry> entries =
                new ArrayList<>(
                        addresses.size()
                );

        for (KnownPeerAddress known :
                addresses) {

            PeerAddress address =
                    known.peerAddress();

            byte[] raw =
                    address.address()
                            .getAddress();

            final int networkId;

            if (raw.length == 4) {

                networkId =
                        AddrV2Network.IPV4.id();

            } else if (raw.length == 16) {

                networkId =
                        AddrV2Network.IPV6.id();

            } else {

                continue;
            }

            entries.add(
                    new AddrV2Entry(
                            timestamp(
                                    known
                            ),
                            address.services(),
                            networkId,
                            raw,
                            address.port()
                    )
            );
        }

        if (entries.isEmpty()) {
            return;
        }

        peer.send(
                BitcoinMessages.addrV2(
                        new AddrV2Message(
                                entries
                        )
                )
        );
    }

    private static PeerAddress toPeerAddress(
            AddrEntry entry
    ) {

        try {

            byte[] raw =
                    entry.address();

            InetAddress address =
                    InetAddress.getByAddress(
                            normalizeLegacyAddress(
                                    raw
                            )
                    );

            if (entry.port() == 0) {
                return null;
            }

            return new PeerAddress(
                    address,
                    entry.port(),
                    entry.services()
            );

        } catch (UnknownHostException
                 | IllegalArgumentException exception) {

            return null;
        }
    }

    private static PeerAddress toPeerAddress(
            AddrV2Entry entry
    ) {

        if (!entry.isGossipEligible()) {
            return null;
        }

        AddrV2Network network =
                entry.network()
                        .orElse(
                                null
                        );

        if (network != AddrV2Network.IPV4
                && network != AddrV2Network.IPV6) {

            /*
             * Current PeerAddress is InetAddress-based.
             *
             * Tor v3, I2P and CJDNS require the future
             * multi-network AddrMan model.
             */
            return null;
        }

        if (entry.port() == 0) {
            return null;
        }

        try {

            InetAddress address =
                    InetAddress.getByAddress(
                            entry.address()
                    );

            return new PeerAddress(
                    address,
                    entry.port(),
                    entry.services()
            );

        } catch (UnknownHostException
                 | IllegalArgumentException exception) {

            return null;
        }
    }

    private static byte[] normalizeLegacyAddress(
            byte[] address
    ) {

        if (address.length != 16) {
            return address;
        }

        boolean mappedIpv4 =
                true;

        for (int i = 0; i < 10; i++) {

            if (address[i] != 0) {

                mappedIpv4 =
                        false;

                break;
            }
        }

        if (mappedIpv4
                && address[10] == (byte) 0xFF
                && address[11] == (byte) 0xFF) {

            return new byte[]{
                    address[12],
                    address[13],
                    address[14],
                    address[15]
            };
        }

        return address;
    }

    private static long timestamp(
            KnownPeerAddress known
    ) {

        long timestamp =
                known.lastSeen()
                        .getEpochSecond();

        if (timestamp < 0) {
            return 0;
        }

        return Math.min(
                timestamp,
                0xFFFF_FFFFL
        );
    }
}