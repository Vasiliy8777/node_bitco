package ru.bitcoin.node.protocol.address;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.*;

class SegwitAddressDecoderTest {
    @Test
    void decodesMiningWalletMainnetP2wpkh() {
        byte[] script = SegwitAddressDecoder.toScriptPubKey(
                "bc1qpjf8xhuttzemn7hla024klrrftqqp607zphkyf", NetworkParametersRegistry.mainnet());
        assertEquals("00140c92735f8b58b3b9faffebd55b7c634ac000e9fe", HexFormat.of().formatHex(script));
    }

    @Test
    void rejectsMainnetAddressOnTestnet() {
        assertThrows(IllegalArgumentException.class, () -> SegwitAddressDecoder.toScriptPubKey(
                "bc1qpjf8xhuttzemn7hla024klrrftqqp607zphkyf", NetworkParametersRegistry.testnet()));
    }

    @Test
    void acceptsBip173P2wpkhVector() {
        assertEquals("0014751e76e8199196d454941c45d1b3a323f1433bd6",
                HexFormat.of().formatHex(SegwitAddressDecoder.toScriptPubKey(
                        "BC1QW508D6QEJXTDG4Y5R3ZARVARY0C5XW7KV8F3T4", NetworkParametersRegistry.mainnet())));
    }

    @Test
    void acceptsBip350TaprootVector() {
        assertEquals("512079be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798",
                HexFormat.of().formatHex(SegwitAddressDecoder.toScriptPubKey(
                        "bc1p0xlxvlhemja6c4dqv22uapctqupfhlxm9h8z3k2e72q4k9hcz7vqzk5jj0", NetworkParametersRegistry.mainnet())));
    }

    @Test
    void rejectsCorruptChecksum() {
        assertThrows(IllegalArgumentException.class, () -> SegwitAddressDecoder.toScriptPubKey(
                "bc1qpjf8xhuttzemn7hla024klrrftqqp607zphkyq", NetworkParametersRegistry.mainnet()));
    }

    @Test
    void rejectsMixedCase() {
        assertThrows(IllegalArgumentException.class, () -> SegwitAddressDecoder.toScriptPubKey(
                "bc1Qpjf8xhuttzemn7hla024klrrftqqp607zphkyf", NetworkParametersRegistry.mainnet()));
    }
}
