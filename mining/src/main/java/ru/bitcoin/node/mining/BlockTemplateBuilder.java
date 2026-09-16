package ru.bitcoin.node.mining;

import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.chain.utxo.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.mining.coinbase.CoinbaseBuilder;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.UnspendableScript;
import ru.bitcoin.node.storage.utxo.*;
import java.util.*;

/** Builds and contextually validates a selected, topologically ordered transaction list.
 * Caller holds the chain lock and supplies the required header version/time/bits.
 * Header difficulty selection and proof of work are not performed by this component.
 */
public final class BlockTemplateBuilder {
    private BlockTemplateBuilder() { }
    /** Selects from a stable, validated mempool snapshot; limit includes the entire block. */
    public static Block fromMempool(BlockIndex parent, BlockIndexLookup lookup, UtxoStore coins,
                                   NetworkParameters parameters, int version, UInt32 time, UInt32 bits,
                                   byte[] payout, byte[] extraNonce,
                                   List<ru.bitcoin.node.mempool.MempoolEntry> snapshot,
                                   long maximumWeight, ru.bitcoin.node.mempool.FeeRate minimumRate) {
        if (maximumWeight <= 0 || maximumWeight > 4_000_000)
            throw new IllegalArgumentException("Invalid block weight limit");
        var coinbase = CoinbaseBuilder.build(Math.addExact(parent.height(),1), parameters, 0,
                payout, extraNonce, List.of());
        // Header and worst-case CompactSize transaction count (9 bytes), both stripped.
        long reservedWeight = Math.addExact(80L * 4 + 9L * 4, TransactionWeight.calculate(coinbase));
        long reservedSigops = TransactionSigOpCost.legacyCost(coinbase);
        if (reservedWeight > maximumWeight || reservedSigops > 80_000)
            throw new IllegalArgumentException("Coinbase exceeds template budget");
        var selected = TransactionSelector.select(snapshot, maximumWeight-reservedWeight,
                80_000-reservedSigops, minimumRate);
        var block = build(parent,lookup,coins,parameters,version,time,bits,payout,extraNonce,selected);
        if (ru.bitcoin.node.consensus.block.BlockWeight.calculate(block) > maximumWeight)
            throw new IllegalStateException("Selected block exceeds requested weight");
        return block;
    }
    public static Block build(BlockIndex parent, BlockIndexLookup lookup, UtxoStore coins,
                              NetworkParameters parameters, int version, UInt32 time, UInt32 bits,
                              byte[] payout, byte[] extraNonce, List<Transaction> selected) {
        selected=List.copyOf(selected);
        long height=Math.addExact(parent.height(),1);
        long mtp=MedianTimePast.calculate(parent,lookup);
        if(time.value()<=mtp) throw new IllegalArgumentException("Template time must exceed MTP");
        var overlay=new UtxoOverlay(coins);
        ru.bitcoin.node.consensus.transaction.UtxoView view=point -> overlay.find(point).map(c -> new UtxoEntry(c.amount(),c.scriptPubKey(),c.height(),c.coinbase()));
        long fees=0;
        for(var tx:selected) {
            if(tx.isCoinbase()) throw new IllegalArgumentException("Selection contains coinbase");
            fees=Math.addExact(fees,ContextualTransactionValidator.validateInputs(tx,height,view).fee());
            for(var input:tx.inputs()) overlay.spend(input.previousOutput());
            for(int i=0;i<tx.outputs().size();i++) {
                var output=tx.outputs().get(i);
                if(!UnspendableScript.isUnspendable(output.scriptPubKey()))
                    overlay.put(new OutPoint(tx.txId(),new UInt32(i)),new StoredUtxo(output.value(),output.scriptPubKey(),height,false));
            }
        }
        List<Transaction> transactions=new ArrayList<>();
        transactions.add(CoinbaseBuilder.build(height,parameters,fees,payout,extraNonce,selected));
        transactions.addAll(selected);
        var header=new BlockHeader(version,parent.hash(),MerkleTree.calculateRoot(transactions.stream().map(Transaction::txId).toList()),time,bits,new UInt32(0));
        var block=new Block(header,transactions);
        var candidate=BlockIndexFactory.createChild(parent,header);
        BlockConnectChangesBuilder.build(block,height,LockTimeCutoff.calculate(height,time.value(),mtp,parameters),
                mtp,coins,parameters,new AncestorMedianTimePastResolver(candidate,lookup));
        return block;
    }
}
