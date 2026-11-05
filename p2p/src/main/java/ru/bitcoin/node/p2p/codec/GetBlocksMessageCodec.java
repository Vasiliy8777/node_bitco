package ru.bitcoin.node.p2p.codec;

import ru.bitcoin.node.p2p.message.GetBlocksMessage;
import ru.bitcoin.node.p2p.message.GetHeadersMessage;
import java.util.Objects;

/** GETBLOCKS and GETHEADERS use the same version/locator/hashStop wire structure. */
public final class GetBlocksMessageCodec {
    private GetBlocksMessageCodec() { }
    public static byte[] encode(GetBlocksMessage message) {
        Objects.requireNonNull(message, "message");
        return GetHeadersMessageCodec.encode(new GetHeadersMessage(message.protocolVersion(), message.locatorHashes(), message.stopHash()));
    }
    public static GetBlocksMessage decode(byte[] payload) {
        var message = GetHeadersMessageCodec.decode(payload);
        return new GetBlocksMessage(message.protocolVersion(), message.locatorHashes(), message.stopHash());
    }
}
