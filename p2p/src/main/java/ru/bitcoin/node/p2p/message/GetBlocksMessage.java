package ru.bitcoin.node.p2p.message;

import ru.bitcoin.node.common.types.Hash256;
import java.util.List;

/** Legacy block-inventory locator request; an empty locator falls back to genesis. */
public record GetBlocksMessage(int protocolVersion, List<Hash256> locatorHashes, Hash256 stopHash) {
    public GetBlocksMessage {
        var checked = new GetHeadersMessage(protocolVersion, locatorHashes, stopHash);
        locatorHashes = checked.locatorHashes();
    }
}
