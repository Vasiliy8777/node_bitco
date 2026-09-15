package ru.bitcoin.node.consensus.block;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.block.Block;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Objects;

/** BIP141 block-level witness commitment; independent of per-input script flags. */
public final class WitnessCommitmentValidator {
    private WitnessCommitmentValidator() { }

    public static void validate(Block block, boolean segwitActive) {
        Objects.requireNonNull(block, "block");
        if (block.transactions().isEmpty() || !block.transactions().getFirst().isCoinbase()) {
            throw new BlockValidationException("Witness validation requires a coinbase");
        }
        byte[] commitment = null;
        var coinbase = block.transactions().getFirst();
        if (segwitActive) {
            for (var output : coinbase.outputs()) {
                byte[] script = output.scriptPubKey();
                if (script.length >= 38 && script[0] == 0x6a && script[1] == 0x24
                        && (script[2] & 0xff) == 0xaa && script[3] == 0x21
                        && (script[4] & 0xff) == 0xa9 && (script[5] & 0xff) == 0xed) {
                    commitment = Arrays.copyOfRange(script, 6, 38); // Highest matching index wins.
                }
            }
        }
        if (commitment == null) {
            if (block.transactions().stream().anyMatch(tx -> tx.hasWitness())) {
                throw new BlockValidationException("Unexpected witness data without active commitment");
            }
            return;
        }
        var witness = coinbase.inputs().getFirst().witness();
        if (witness.size() != 1 || witness.item(0).length != 32) {
            throw new BlockValidationException("Coinbase witness must contain one 32-byte reserved value");
        }
        var hashes = new ArrayList<Hash256>();
        hashes.add(new Hash256(new byte[32]));
        for (int i = 1; i < block.transactions().size(); i++) {
            hashes.add(block.transactions().get(i).wtxId());
        }
        byte[] preimage = new byte[64];
        System.arraycopy(MerkleTree.calculateRoot(hashes).bytes(), 0, preimage, 0, 32);
        System.arraycopy(witness.item(0), 0, preimage, 32, 32);
        if (!Arrays.equals(commitment, Hash256Digest.hash(preimage).bytes())) {
            throw new BlockValidationException("Invalid witness commitment");
        }
    }
}
