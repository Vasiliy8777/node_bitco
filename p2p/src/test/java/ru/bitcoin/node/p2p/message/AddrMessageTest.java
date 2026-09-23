package ru.bitcoin.node.p2p.message;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddrMessageTest {

    @Test
    void bitcoinMessagesWrapsAndDecodesAddr()
            throws Exception {

        AddrMessage original =
                new AddrMessage(
                        List.of(
                                AddrEntry.fromIp(
                                        1_700_000_000L,
                                        9L,
                                        InetAddress.getByName(
                                                "127.0.0.1"
                                        ),
                                        18444
                                )
                        )
                );

        BitcoinMessage wireMessage =
                BitcoinMessages.addr(
                        original
                );

        assertEquals(
                "addr",
                wireMessage.command()
        );

        AddrMessage decoded =
                BitcoinMessages.decodeAddr(
                        wireMessage
                );

        assertEquals(
                1,
                decoded.size()
        );

        assertEquals(
                1_700_000_000L,
                decoded.addresses()
                        .get(0)
                        .timestamp()
        );

        assertEquals(
                18444,
                decoded.addresses()
                        .get(0)
                        .port()
        );
    }

    @Test
    void decodeAddrRejectsWrongCommand() {

        BitcoinMessage message =
                new BitcoinMessage(
                        "inv",
                        new byte[]{0x00}
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> BitcoinMessages.decodeAddr(
                        message
                )
        );
    }
}