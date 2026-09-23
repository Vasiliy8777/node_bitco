package ru.bitcoin.node.p2p.message;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class AddrV2MessageTest {

    @Test
    void bitcoinMessagesWrapsAndDecodesAddrV2() {

        AddrV2Entry entry =
                new AddrV2Entry(
                        1_700_000_000L,
                        9L,
                        AddrV2Network.IPV4.id(),
                        new byte[]{
                                127, 0, 0, 1
                        },
                        18444
                );

        BitcoinMessage wireMessage =
                BitcoinMessages.addrV2(
                        new AddrV2Message(
                                List.of(entry)
                        )
                );

        assertEquals(
                "addrv2",
                wireMessage.command()
        );

        AddrV2Message decoded =
                BitcoinMessages.decodeAddrV2(
                        wireMessage
                );

        assertEquals(
                1,
                decoded.size()
        );

        assertEquals(
                entry,
                decoded.addresses().get(0)
        );
    }

    @Test
    void decodeAddrV2RejectsWrongCommand() {

        BitcoinMessage message =
                new BitcoinMessage(
                        "addr",
                        new byte[]{0x00}
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> BitcoinMessages.decodeAddrV2(
                        message
                )
        );
    }
}