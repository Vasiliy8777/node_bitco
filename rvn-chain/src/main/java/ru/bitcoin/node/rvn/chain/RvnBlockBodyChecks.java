package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.protocol.RvnWire;
import java.util.*;

/** Commitment/shape checks for downloaded bodies. Does NOT verify spends, signatures,
 * coinbase reward, deployment-dependent scripts or RVN asset transitions. */
public final class RvnBlockBodyChecks {
    public static final long COIN = 100_000_000L;
    public static final long MAX_MONEY = 21_000_000_000L * COIN;
    private RvnBlockBodyChecks() {}

    public static void requireValid(RvnBlock block, boolean assetsActive) {
        var transactions = block.transactions();
        if (transactions.isEmpty() || !transactions.getFirst().isCoinbase())
            throw new SecurityException("RVN block must start with coinbase");
        long weight = Math.addExact(Math.multiplyExact(3, RvnBlockCodec.size(block, false)), RvnBlockCodec.size(block, true));
        if (weight > (assetsActive ? 8_000_000 : 4_000_000)) throw new SecurityException("RVN block weight limit");
        Set<Hash256> txids = new HashSet<>();
        for (int i = 0; i < transactions.size(); i++) {
            var tx = transactions.get(i);
            if (i > 0 && tx.isCoinbase()) throw new SecurityException("Multiple RVN coinbase transactions");
            if (tx.inputs().isEmpty() || tx.outputs().isEmpty()) throw new SecurityException("Empty RVN transaction inputs/outputs");
            if (!txids.add(tx.txId())) throw new SecurityException("Duplicate RVN transaction ID");
            long total = 0;
            for (var output : tx.outputs()) {
                if (output.value() < 0 || output.value() > MAX_MONEY || total > MAX_MONEY - output.value())
                    throw new SecurityException("RVN transaction output amount range");
                total += output.value();
            }
            if (tx.isCoinbase()) {
                int size = tx.inputs().getFirst().scriptSigLength();
                if (size < 2 || size > 100) throw new SecurityException("RVN coinbase script length");
            } else {
                Set<OutPoint> spent = new HashSet<>();
                for (var input : tx.inputs()) {
                    if (input.previousOutput().isCoinbase() || !spent.add(input.previousOutput()))
                        throw new SecurityException("Invalid/duplicate RVN transaction input");
                }
            }
        }
        var merkle = MerkleTree.calculateRootWithMutation(transactions.stream().map(Transaction::txId).toList());
        if (merkle.mutated() || !Arrays.equals(merkle.root().bytes(), block.header().merkleRootWire()))
            throw new SecurityException("RVN transaction Merkle root mismatch/mutation");
        requireWitnessCommitment(transactions);
    }

    private static void requireWitnessCommitment(List<Transaction> transactions) {
        byte[] commitment = null;
        for (var output : transactions.getFirst().outputs()) {
            byte[] script = output.scriptPubKey();
            if (script.length >= 38 && script[0] == 0x6a && script[1] == 0x24 && (script[2] & 255) == 0xaa
                    && (script[3] & 255) == 0x21 && (script[4] & 255) == 0xa9 && (script[5] & 255) == 0xed)
                commitment = Arrays.copyOfRange(script, 6, 38); // Core selects the last matching output.
        }
        if (commitment == null) {
            if (transactions.stream().anyMatch(Transaction::hasWitness))
                throw new SecurityException("Unexpected RVN witness without commitment");
            return;
        }
        var reserved = transactions.getFirst().inputs().getFirst().witness();
        if (reserved.size() != 1 || reserved.itemLength(0) != 32)
            throw new SecurityException("RVN witness reserved value must be 32 bytes");
        List<Hash256> leaves = new ArrayList<>(); leaves.add(new Hash256(new byte[32]));
        for (int i = 1; i < transactions.size(); i++) leaves.add(transactions.get(i).wtxId());
        byte[] preimage = new byte[64];
        System.arraycopy(MerkleTree.calculateRoot(leaves).bytes(), 0, preimage, 0, 32);
        System.arraycopy(reserved.item(0), 0, preimage, 32, 32);
        if (!Arrays.equals(commitment, RvnWire.doubleSha(preimage)))
            throw new SecurityException("RVN witness commitment mismatch");
    }
}
