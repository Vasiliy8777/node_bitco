package ru.bitcoin.node.p2p.message;

import ru.bitcoin.node.protocol.block.BlockHeader;

import java.util.List;

public final class HeadersMessage {

    public static final int MAX_HEADERS = 2_000;

    private final List<BlockHeader> headers;

    public HeadersMessage(
            List<BlockHeader> headers
    ) {
        if (headers == null) {
            throw new IllegalArgumentException(
                    "headers must not be null"
            );
        }

        if (headers.size() > MAX_HEADERS) {
            throw new IllegalArgumentException(
                    "Too many headers: "
                            + headers.size()
            );
        }

        if (headers.stream().anyMatch(
                header -> header == null
        )) {
            throw new IllegalArgumentException(
                    "headers must not contain null"
            );
        }

        this.headers =
                List.copyOf(headers);
    }

    public List<BlockHeader> headers() {
        return headers;
    }

    public int size() {
        return headers.size();
    }

    public boolean isEmpty() {
        return headers.isEmpty();
    }
}