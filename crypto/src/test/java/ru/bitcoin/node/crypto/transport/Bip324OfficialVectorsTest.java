package ru.bitcoin.node.crypto.transport;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.crypto.secp256k1.PrivateKey;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Conformance against the official bitcoin/bips BIP324 v1.0.2 CSV vectors.
 */
class Bip324OfficialVectorsTest {
    private static final HexFormat HEX = HexFormat.of();
    private static final byte[] MAINNET_MAGIC = {(byte) 0xf9, (byte) 0xbe, (byte) 0xb4, (byte) 0xd9};

    @Test
    void officialDecodeSmokeVector() {
        assertEquals("edd1fd3e327ce90cc7a3542614289aee9682003e9cf7dcc9cf2ca9743be5aa0c",
                HEX.formatHex(Bip324ElligatorSwift.decodeX(HEX.parseHex("0".repeat(128)))));
    }

    @Test
    void officialInverseSmokeVector() {
        BigInteger u = new BigInteger("05ff6bdad900fc3261bc7fe34e2fb0f569f06e091ae437d3a52e9da0cbfb9590", 16);
        BigInteger x = new BigInteger("80cdf63774ec7022c89a5a8558e373a279170285e0ab27412dbce510bdfe23fc", 16);
        assertNull(Bip324ElligatorSwift.xSwiftEcInv(x, u, 0));
        assertEquals(new BigInteger("45654798ece071ba79286d04f7f3eb1c3f1d17dd883610f2ad2efd82a287466b", 16),
                Bip324ElligatorSwift.xSwiftEcInv(x, u, 2));
    }

    @Test
    void fullOfficialCorpusWhenProvided() throws Exception {
        String dir = System.getProperty("bip324.vectors.dir");
        if (dir == null || dir.isBlank()) return; // stage gate supplies and requires the corpus
        Path base = Path.of(dir);
        verifyDecode(base.resolve("ellswift_decode_test_vectors.csv"));
        verifyInverse(base.resolve("xswiftec_inv_test_vectors.csv"));
        verifyPackets(base.resolve("packet_encoding_test_vectors.csv"));
    }

    private static void verifyDecode(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.size() >= 70, "official decode corpus unexpectedly short");
        for (int i = 1; i < lines.size(); i++) {
            String[] c = lines.get(i).split(",", -1);
            assertEquals(c[1], HEX.formatHex(Bip324ElligatorSwift.decodeX(HEX.parseHex(c[0]))), "decode row " + i);
        }
    }

    private static void verifyInverse(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.size() >= 30, "official inverse corpus unexpectedly short");
        for (int i = 1; i < lines.size(); i++) {
            String[] c = lines.get(i).split(",", -1);
            BigInteger u = new BigInteger(c[0], 16), x = new BigInteger(c[1], 16);
            for (int kase = 0; kase < 8; kase++) {
                BigInteger actual = Bip324ElligatorSwift.xSwiftEcInv(x, u, kase);
                String expected = c[2 + kase];
                if (expected.isEmpty()) assertNull(actual, "inverse row " + i + " case " + kase);
                else assertEquals(new BigInteger(expected, 16), actual, "inverse row " + i + " case " + kase);
            }
        }
    }

    private static void verifyPackets(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file);
        assertTrue(lines.size() >= 7, "official packet corpus unexpectedly short");
        for (int row = 1; row < lines.size(); row++) {
            String[] c = lines.get(row).split(",", -1);
            assertTrue(c.length >= 22, "packet row " + row + " has " + c.length + " columns");
            int idx = Integer.parseInt(c[0]);
            PrivateKey priv = new PrivateKey(new BigInteger(c[1], 16));
            byte[] ours = HEX.parseHex(c[2]), theirs = HEX.parseHex(c[3]);
            boolean initiating = c[4].equals("1");
            byte[] unit = HEX.parseHex(c[5]);
            int multiply = Integer.parseInt(c[6]);
            byte[] aad = HEX.parseHex(c[7]);
            boolean ignore = c[8].equals("1");
            byte[] secret = Bip324ElligatorSwift.ecdhSecret(priv, theirs, ours, initiating);
            Bip324KeyMaterial keys = Bip324KeyMaterial.derive(secret, MAINNET_MAGIC, initiating);
            Arrays.fill(secret, (byte) 0);

            assertArrayEquals(HEX.parseHex(c[19]), keys.sessionId(), "session row " + row);
            assertArrayEquals(HEX.parseHex(c[17]), keys.sendGarbageTerminator(), "send terminator row " + row);
            assertArrayEquals(HEX.parseHex(c[18]), keys.receiveGarbageTerminator(), "recv terminator row " + row);

            Bip324PacketCipher cipher = new Bip324PacketCipher(keys);
            for (int i = 0; i < idx; i++) cipher.encrypt(new byte[0], new byte[0], false);
            byte[] contents = repeat(unit, multiply);
            byte[] ciphertext = cipher.encrypt(contents, aad, ignore);
            if (!c[20].isEmpty()) assertArrayEquals(HEX.parseHex(c[20]), ciphertext, "ciphertext row " + row);
            if (!c[21].isEmpty()) {
                byte[] suffix = HEX.parseHex(c[21]);
                assertTrue(ciphertext.length >= suffix.length);
                assertArrayEquals(suffix, Arrays.copyOfRange(ciphertext, ciphertext.length - suffix.length, ciphertext.length),
                        "ciphertext suffix row " + row);
            }
        }
    }

    private static byte[] repeat(byte[] unit, int count) {
        if (count < 0 || (long) unit.length * count > Bip324PacketCipher.MAX_CONTENTS_LENGTH)
            throw new IllegalArgumentException("invalid vector multiply");
        byte[] out = new byte[unit.length * count];
        for (int i = 0; i < count; i++) System.arraycopy(unit, 0, out, i * unit.length, unit.length);
        return out;
    }
}
