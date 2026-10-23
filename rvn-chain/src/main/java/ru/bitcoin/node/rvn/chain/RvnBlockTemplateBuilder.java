package ru.bitcoin.node.rvn.chain;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.rvn.protocol.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** Candidate assembly only. The caller must supply a validated parent, contextual
 * version/time/bits, dependency-ordered validated transactions and their actual fees.
 * Neither this builder nor body commitment checks validate UTXO, assets or PoW.
 */
public final class RvnBlockTemplateBuilder {
    private RvnBlockTemplateBuilder() {}

    /** Compatibility path without BIP68 activation/branch context. */
    public static RvnBlock buildWithNativeChecks(int version, byte[] parentHashWire, long height,
            long timestamp, long bits, long extraNonce, RvnPayoutAddress payout,
            List<Transaction> transactions, RvnCoinView parent, boolean assetsActive,
            long lockTimeCutoff, int scriptFlags) {
        return buildWithNativeChecks(version, parentHashWire, timestamp, bits, extraNonce, payout,
                transactions, parent, assetsActive, new RvnSpendContext(height, lockTimeCutoff, 0, false,
                        scriptFlags, ignored -> { throw new IllegalStateException("No RVN branch MTP supplied"); }));
    }

    /** Candidate assembly with scripts, locktime, branch-specific BIP68 and the
     * complete native sigop budget. Assets and header context remain external. */
    public static RvnBlock buildWithNativeChecks(int version, byte[] parentHashWire,
            long timestamp, long bits, long extraNonce, RvnPayoutAddress payout,
            List<Transaction> transactions, RvnCoinView parent, boolean assetsActive, RvnSpendContext context) {
        var selected = List.copyOf(transactions);
        var changes = RvnNativeTransactionChecks.transactions(context, selected, parent);
        var block = build(version, parentHashWire, context.height(), timestamp, bits, extraNonce,
                payout, selected, changes.fees(), assetsActive);
        RvnNativeTransactionChecks.block(block, context, assetsActive, parent);
        return block;
    }

    /** Derives native fees from a stable parent coin snapshot. The caller still
     * must validate scripts, locks, assets and contextual header fields. */
    public static RvnBlock buildFromCoins(int version, byte[] parentHashWire, long height,
                                          long timestamp, long bits, long extraNonce,
                                          RvnPayoutAddress payout, List<Transaction> transactions,
                                          RvnCoinView parent, boolean assetsActive) {
        var selected = List.copyOf(transactions);
        var changes = RvnCoinAccounting.transactions(height, selected, parent);
        var block = build(version, parentHashWire, height, timestamp, bits, extraNonce,
                payout, selected, changes.fees(), assetsActive);
        RvnCoinAccounting.block(block, height, assetsActive, parent);
        return block;
    }

    public static RvnBlock build(int version, byte[] parentHashWire, long height,
                                 long timestamp, long bits, long extraNonce,
                                 RvnPayoutAddress payout, List<Transaction> transactions,
                                 long fees, boolean assetsActive) {
        if (parentHashWire == null || parentHashWire.length != 32)
            throw new IllegalArgumentException("RVN parent hash must be 32 bytes");
        requireUint32(height, "height");
        requireUint32(timestamp, "timestamp");
        requireUint32(bits, "bits");
        if (timestamp < RvnActivation.MAINNET_KAWPOW_TIME)
            throw new IllegalArgumentException("RVN template requires KawPoW timestamp");
        var selected = List.copyOf(transactions);
        if (selected.isEmpty() && fees != 0)
            throw new IllegalArgumentException("Empty RVN template cannot collect fees");
        if (selected.stream().anyMatch(Transaction::isCoinbase))
            throw new IllegalArgumentException("Selected RVN transactions contain coinbase");

        Transaction coinbase = RvnCoinbase.create(height, extraNonce, fees, payout);
        if (selected.stream().anyMatch(Transaction::hasWitness)) {
            byte[] reserved = new byte[32];
            var leaves = new ArrayList<Hash256>();
            leaves.add(new Hash256(new byte[32]));
            for (var tx : selected) leaves.add(tx.wtxId());
            byte[] preimage = new byte[64];
            System.arraycopy(MerkleTree.calculateRoot(leaves).bytes(), 0, preimage, 0, 32);
            byte[] commitmentScript = new byte[38];
            byte[] prefix = {0x6a, 0x24, (byte) 0xaa, 0x21, (byte) 0xa9, (byte) 0xed};
            System.arraycopy(prefix, 0, commitmentScript, 0, prefix.length);
            System.arraycopy(RvnWire.doubleSha(preimage), 0, commitmentScript, 6, 32);
            var input = coinbase.inputs().getFirst();
            var outputs = new ArrayList<>(coinbase.outputs());
            outputs.add(new TxOut(0, commitmentScript));
            coinbase = new Transaction(coinbase.version(), List.of(new TxIn(input.previousOutput(),
                    input.scriptSig(), input.sequence(), new Witness(List.of(reserved)))),
                    outputs, coinbase.lockTime());
        }
        var body = new ArrayList<Transaction>();
        body.add(coinbase);
        body.addAll(selected);
        byte[] merkle = MerkleTree.calculateRoot(body.stream().map(Transaction::txId).toList()).bytes();
        byte[] header = ByteBuffer.allocate(RvnBlockHeader.KAWPOW_SIZE).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(version).put(parentHashWire).put(merkle).putInt((int) timestamp)
                .putInt((int) bits).putInt((int) height).array();
        try {
            var block = new RvnBlock(RvnBlockHeader.parse(header, RvnActivation.MAINNET_KAWPOW_TIME), body);
            RvnBlockBodyChecks.requireValid(block, assetsActive);
            return block;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Installs worker output; the caller must independently verify its mix,
     * target and parent freshness before accepting or relaying the candidate. */
    public static RvnBlock withProof(RvnBlock template, long nonce64Bits, byte[] mixHashWire) {
        if (!template.header().isKawpow()) throw new IllegalArgumentException("RVN KawPoW template required");
        if (mixHashWire == null || mixHashWire.length != 32)
            throw new IllegalArgumentException("RVN mix hash must be 32 bytes");
        byte[] header = template.header().serialize();
        ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).position(80).putLong(nonce64Bits).put(mixHashWire);
        try {
            return new RvnBlock(RvnBlockHeader.parse(header, RvnActivation.MAINNET_KAWPOW_TIME), template.transactions());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void requireUint32(long value, String field) {
        if (value < 0 || value > 0xffff_ffffL) throw new IllegalArgumentException("RVN template " + field);
    }
}
