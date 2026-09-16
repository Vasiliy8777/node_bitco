package ru.bitcoin.node.mining.coinbase;

import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.money.*;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.ScriptNumber;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** Builds a coinbase for an already validated transaction selection and fee total. */
public final class CoinbaseBuilder {
    private CoinbaseBuilder() { }

    public static Transaction build(long height, NetworkParameters parameters, long fees,
                                    byte[] payoutScript, byte[] extraNonce, List<Transaction> transactions) {
        Objects.requireNonNull(parameters); Objects.requireNonNull(payoutScript);
        Objects.requireNonNull(extraNonce); transactions = List.copyOf(transactions);
        if (height < 0 || !Money.isValidAmount(fees)) throw new IllegalArgumentException("Invalid height or fees");
        long reward = Math.addExact(BlockSubsidy.calculate(height, parameters), fees);
        if (!Money.isValidAmount(reward)) throw new IllegalArgumentException("Coinbase reward exceeds money range");
        if (transactions.stream().anyMatch(Transaction::isCoinbase)) throw new IllegalArgumentException("Selection contains coinbase");
        boolean segwit = height >= parameters.segwitHeight();
        if (!segwit && transactions.stream().anyMatch(Transaction::hasWitness)) throw new IllegalArgumentException("Witness before SegWit");
        var script = new ByteArrayOutputStream();
        if (height == 0) script.write(0);
        else if (height <= 16) script.write((int)height + 0x50);
        else { byte[] number = ScriptNumber.encode(height); script.write(number.length); script.writeBytes(number); }
        script.writeBytes(extraNonce);
        if (script.size() < 2) script.write(0);
        if (script.size() > 100) throw new IllegalArgumentException("Coinbase scriptSig exceeds 100 bytes");
        List<TxOut> outputs = new ArrayList<>();
        outputs.add(new TxOut(reward, payoutScript));
        TxIn input;
        if (segwit) {
            byte[] reserved = new byte[32];
            List<Hash256> leaves = new ArrayList<>(); leaves.add(new Hash256(new byte[32]));
            for (var tx : transactions) leaves.add(tx.wtxId());
            byte[] preimage = new byte[64];
            System.arraycopy(MerkleTree.calculateRoot(leaves).bytes(), 0, preimage, 0, 32);
            byte[] commitment = new byte[38];
            System.arraycopy(HexFormat.of().parseHex("6a24aa21a9ed"), 0, commitment, 0, 6);
            System.arraycopy(Hash256Digest.hashBytes(preimage), 0, commitment, 6, 32);
            outputs.add(new TxOut(0, commitment));
            input = new TxIn(OutPoint.coinbase(), script.toByteArray(), TxIn.FINAL_SEQUENCE, new Witness(List.of(reserved)));
        } else input = new TxIn(OutPoint.coinbase(), script.toByteArray(), TxIn.FINAL_SEQUENCE);
        return new Transaction(2, List.of(input), outputs, new UInt32(0));
    }
}
