package ru.bitcoin.node.p2p.message;

import ru.bitcoin.node.p2p.codec.*;

public final class BitcoinMessages {

    private BitcoinMessages() {
    }

    public static BitcoinMessage version(
            VersionMessage versionMessage
    ) {
        if (versionMessage == null) {
            throw new IllegalArgumentException(
                    "versionMessage must not be null"
            );
        }

        return new BitcoinMessage(
                "version",
                VersionMessageCodec.encode(
                        versionMessage
                )
        );
    }

    public static BitcoinMessage verack() {
        return new BitcoinMessage(
                "verack",
                new byte[0]
        );
    }
    public static VersionMessage decodeVersion(
            BitcoinMessage message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        if (!"version".equals(message.command())) {
            throw new IllegalArgumentException(
                    "Expected version message but received: "
                            + message.command()
            );
        }

        return VersionMessageCodec.decode(
                message.payload()
        );
    }
    public static BitcoinMessage wtxidRelay() {
        return new BitcoinMessage(
                "wtxidrelay",
                new byte[0]
        );
    }

    public static BitcoinMessage sendAddrV2() {
        return new BitcoinMessage(
                "sendaddrv2",
                new byte[0]
        );
    }
    public static BitcoinMessage getHeaders(
            GetHeadersMessage getHeadersMessage
    ) {
        if (getHeadersMessage == null) {
            throw new IllegalArgumentException(
                    "getHeadersMessage must not be null"
            );
        }

        return new BitcoinMessage(
                "getheaders",
                GetHeadersMessageCodec.encode(
                        getHeadersMessage
                )
        );
    }
    public static HeadersMessage decodeHeaders(
            BitcoinMessage message
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        if (!"headers".equals(
                message.command()
        )) {
            throw new IllegalArgumentException(
                    "Expected headers message but received: "
                            + message.command()
            );
        }

        return HeadersMessageCodec.decode(
                message.payload()
        );
    }
    public static BitcoinMessage ping(
            PingMessage pingMessage
    ) {
        if (pingMessage == null) {
            throw new IllegalArgumentException(
                    "pingMessage must not be null"
            );
        }

        return new BitcoinMessage(
                "ping",
                PingPongMessageCodec.encodePing(
                        pingMessage
                )
        );
    }

    public static BitcoinMessage pong(
            PongMessage pongMessage
    ) {
        if (pongMessage == null) {
            throw new IllegalArgumentException(
                    "pongMessage must not be null"
            );
        }

        return new BitcoinMessage(
                "pong",
                PingPongMessageCodec.encodePong(
                        pongMessage
                )
        );
    }

    public static PingMessage decodePing(
            BitcoinMessage message
    ) {
        requireCommand(
                message,
                "ping"
        );

        return PingPongMessageCodec.decodePing(
                message.payload()
        );
    }

    public static PongMessage decodePong(
            BitcoinMessage message
    ) {
        requireCommand(
                message,
                "pong"
        );

        return PingPongMessageCodec.decodePong(
                message.payload()
        );
    }

    public static BitcoinMessage block(
            BlockMessage blockMessage
    ) {
        if (blockMessage == null) {
            throw new IllegalArgumentException(
                    "blockMessage must not be null"
            );
        }

        return new BitcoinMessage(
                "block",
                BlockMessageCodec.encode(
                        blockMessage
                )
        );
    }

    public static BlockMessage decodeBlock(
            BitcoinMessage message
    ) {
        requireCommand(
                message,
                "block"
        );

        return BlockMessageCodec.decode(
                message.payload()
        );
    }

    public static BitcoinMessage getData(
            GetDataMessage getDataMessage
    ) {
        if (getDataMessage == null) {
            throw new IllegalArgumentException(
                    "getDataMessage must not be null"
            );
        }

        return new BitcoinMessage(
                "getdata",
                GetDataMessageCodec.encode(
                        getDataMessage
                )
        );
    }

    public static GetDataMessage decodeGetData(
            BitcoinMessage message
    ) {
        requireCommand(
                message,
                "getdata"
        );

        return GetDataMessageCodec.decode(
                message.payload()
        );
    }

    private static void requireCommand(
            BitcoinMessage message,
            String expectedCommand
    ) {
        if (message == null) {
            throw new IllegalArgumentException(
                    "message must not be null"
            );
        }

        if (!expectedCommand.equals(
                message.command()
        )) {
            throw new IllegalArgumentException(
                    "Expected "
                            + expectedCommand
                            + " message but received: "
                            + message.command()
            );
        }
    }
}