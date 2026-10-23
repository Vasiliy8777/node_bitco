package ru.bitcoin.node.rvn.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.protocol.*;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;

class RvnBlockCodecTest {
    static RvnBlock block(List<Transaction> transactions) throws Exception {
        byte[] header = new byte[80]; header[0] = 4;
        System.arraycopy(MerkleTree.calculateRoot(transactions.stream().map(Transaction::txId).toList()).bytes(), 0, header, 36, 32);
        return new RvnBlock(RvnBlockHeader.parse(header, RvnActivation.MAINNET_KAWPOW_TIME), transactions);
    }
    static Transaction coinbase() { return RvnCoinbase.create(1, 0, 0,
            RvnPayoutAddress.parse(RvnPayoutAndCoinbaseTest.PUBLIC_CORE_ADDRESS, RvnNetwork.MAINNET)); }
    static Transaction spend(byte[] witness) {
        byte[] hash = new byte[32]; hash[0] = 1;
        return new Transaction(2, List.of(new TxIn(new OutPoint(new Hash256(hash), new UInt32(0)), new byte[0],
                TxIn.FINAL_SEQUENCE, witness == null ? Witness.EMPTY : new Witness(List.of(witness)))),
                List.of(new TxOut(1, new byte[]{0x51})), new UInt32(0));
    }

    @Test
    void pinnedCoreGenesisParsesRoundTripsAndMatchesPublishedMerkleRoot() throws Exception {
        String hex;
        try (var in = Objects.requireNonNull(getClass().getResourceAsStream("/core-reference/mainnet-genesis.hex"))) {
            hex = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).strip();
        }
        byte[] bytes = HexFormat.of().parseHex(hex);
        var block = RvnBlockCodec.parse(bytes);
        assertArrayEquals(bytes, RvnBlockCodec.serialize(block));
        assertEquals(1, block.transactions().size()); assertTrue(block.transactions().getFirst().isCoinbase());
        assertEquals(5000 * RvnBlockBodyChecks.COIN, block.transactions().getFirst().outputs().getFirst().value());
        assertEquals("28ff00a867739a352523808d301f504bc4547699398d70faf2266a8bae5f3516",
                block.transactions().getFirst().txId().toDisplayHex());
        RvnBlockBodyChecks.requireValid(block, false);
        assertThrows(IOException.class, () -> RvnBlockCodec.parse(Arrays.copyOf(bytes, bytes.length - 1)));
        assertThrows(IOException.class, () -> RvnBlockCodec.parse(Arrays.copyOf(bytes, bytes.length + 1)));
    }

    @Test
    void malformedCountsCoinbaseAndMerkleCannotPassBodyChecks() throws Exception {
        var valid = block(List.of(coinbase(), spend(null)));
        RvnBlockBodyChecks.requireValid(RvnBlockCodec.parse(RvnBlockCodec.serialize(valid)), false);
        var duplicate = new RvnBlock(valid.header(), List.of(coinbase(), coinbase()));
        assertThrows(SecurityException.class, () -> RvnBlockBodyChecks.requireValid(duplicate, false));
        assertThrows(SecurityException.class, () -> RvnBlockBodyChecks.requireValid(block(List.of(spend(null))), false));
        byte[] bytes = RvnBlockCodec.serialize(valid); bytes[36] ^= 1;
        var corrupted = RvnBlockCodec.parse(bytes);
        assertThrows(SecurityException.class, () -> RvnBlockBodyChecks.requireValid(corrupted, false));
        byte[] hugeCount = Arrays.copyOf(bytes, 89); hugeCount[80] = (byte) 0xff;
        Arrays.fill(hugeCount, 81, 89, (byte) 0xff);
        assertThrows(IOException.class, () -> RvnBlockCodec.parse(hugeCount));
        // Native RVN amounts are not capped at Bitcoin's 21 million coins.
        var rich = new Transaction(1, coinbase().inputs(), List.of(new TxOut(25_000_000L * RvnBlockBodyChecks.COIN,
                new byte[]{0x51})), new UInt32(0));
        RvnBlockBodyChecks.requireValid(block(List.of(rich)), false);
        var negative = new Transaction(1, coinbase().inputs(), List.of(new TxOut(-1, new byte[]{0x51})), new UInt32(0));
        assertThrows(SecurityException.class, () -> RvnBlockBodyChecks.requireValid(block(List.of(negative)), false));
    }

    @Test
    void witnessCommitmentBindsWitnessAndRIP2AllowsLargerBodies() throws Exception {
        var tx = spend(new byte[4_100_000]);
        byte[] reserved = new byte[32], preimage = new byte[64];
        System.arraycopy(MerkleTree.calculateRoot(List.of(new Hash256(new byte[32]), tx.wtxId())).bytes(), 0, preimage, 0, 32);
        byte[] script = new byte[38];
        System.arraycopy(HexFormat.of().parseHex("6a24aa21a9ed"), 0, script, 0, 6);
        System.arraycopy(RvnWire.doubleSha(preimage), 0, script, 6, 32);
        var base = coinbase(); var input = base.inputs().getFirst();
        var cb = new Transaction(1, List.of(new TxIn(input.previousOutput(), input.scriptSig(), input.sequence(),
                new Witness(List.of(reserved)))), List.of(base.outputs().getFirst(), new TxOut(0, script)), new UInt32(0));
        var full = block(List.of(cb, tx));
        assertTrue(RvnBlockCodec.size(full, true) > 4_000_000);
        RvnBlockBodyChecks.requireValid(RvnBlockCodec.parse(RvnBlockCodec.serialize(full)), true);
        assertThrows(SecurityException.class, () -> RvnBlockBodyChecks.requireValid(full, false));
        var changed = spend(new byte[]{1}); // same txid, different witness
        assertEquals(tx.txId(), changed.txId());
        assertThrows(SecurityException.class, () -> RvnBlockBodyChecks.requireValid(new RvnBlock(full.header(), List.of(cb, changed)), true));
        assertThrows(SecurityException.class, () -> RvnBlockBodyChecks.requireValid(block(List.of(base, spend(new byte[]{1}))), true));
    }
}
