package ru.bitcoin.node.mining;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.protocol.transaction.Transaction;
import java.math.BigInteger;
import java.util.*;

/** Greedy ancestor-package selection from a validated, conflict-free mempool snapshot.
 * Budgets exclude the header and coinbase; the completed block still needs validation.
 */
public final class TransactionSelector {
    private TransactionSelector() { }
    public static List<Transaction> select(List<MempoolEntry> snapshot, long weightBudget,
                                           long sigopsBudget, FeeRate minimumRate) {
        if(weightBudget<0 || sigopsBudget<0) throw new IllegalArgumentException("Negative budget");
        Objects.requireNonNull(minimumRate);
        Map<Hash256,MempoolEntry> pool=new LinkedHashMap<>();
        for(var entry:List.copyOf(snapshot)) {
            if(entry.fee()<0 || entry.weight()<=0 || entry.sigOpCost()<0 || entry.transaction().isCoinbase())
                throw new IllegalArgumentException("Invalid mempool entry");
            if(pool.put(entry.transaction().txId(),entry)!=null) throw new IllegalArgumentException("Duplicate txid");
        }
        Set<Hash256> chosen=new HashSet<>(); List<Transaction> result=new ArrayList<>();
        while(true) {
            LinkedHashSet<Hash256> best=null; long bestFee=0,bestSize=1,bestWeight=0,bestOps=0;
            for(var id:pool.keySet()) {
                if(chosen.contains(id)) continue;
                var pack=new LinkedHashSet<Hash256>();
                ancestors(id,pool,chosen,new HashSet<>(),pack);
                long fee=0,size=0,weight=0,ops=0;
                for(var member:pack) {
                    var e=pool.get(member); fee=Math.addExact(fee,e.fee()); size=Math.addExact(size,e.virtualSize());
                    weight=Math.addExact(weight,e.weight()); ops=Math.addExact(ops,e.sigOpCost());
                }
                if(weight>weightBudget || ops>sigopsBudget || fee<minimumRate.feeForVSize(size)) continue;
                if(best==null || BigInteger.valueOf(fee).multiply(BigInteger.valueOf(bestSize)).compareTo(
                        BigInteger.valueOf(bestFee).multiply(BigInteger.valueOf(size)))>0) {
                    best=pack; bestFee=fee; bestSize=size; bestWeight=weight; bestOps=ops;
                }
            }
            if(best==null) return List.copyOf(result);
            for(var id:best) { chosen.add(id); result.add(pool.get(id).transaction()); }
            weightBudget-=bestWeight; sigopsBudget-=bestOps;
        }
    }
    private static void ancestors(Hash256 id,Map<Hash256,MempoolEntry> pool,Set<Hash256> chosen,
                                  Set<Hash256> visiting,LinkedHashSet<Hash256> result) {
        if(chosen.contains(id) || result.contains(id) || !pool.containsKey(id)) return;
        if(!visiting.add(id)) throw new IllegalArgumentException("Cyclic mempool dependencies");
        for(var in:pool.get(id).transaction().inputs()) ancestors(in.previousOutput().transactionId(),pool,chosen,visiting,result);
        visiting.remove(id); result.add(id);
    }
}
