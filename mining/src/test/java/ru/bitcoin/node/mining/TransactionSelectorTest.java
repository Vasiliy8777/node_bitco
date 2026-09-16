package ru.bitcoin.node.mining;
import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.mempool.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class TransactionSelectorTest {
    private static Transaction tx(Hash256 parent,int tag) {
        return new Transaction(2,List.of(new TxIn(new OutPoint(parent,new UInt32(0)),new byte[0],TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(tag,new byte[]{0x51})),new UInt32(0));
    }
    private static MempoolEntry entry(Transaction tx,long fee,long ops) {
        return new MempoolEntry(tx,fee,TransactionWeight.calculate(tx),0,ops);
    }
    @Test void childPaysForParentAndParentsPrecedeChildrenRegardlessOfSnapshotOrder() {
        var parent=tx(Hash256.fromDisplayHex("11".repeat(32)),1);
        var child=tx(parent.txId(),2);
        var independent=tx(Hash256.fromDisplayHex("22".repeat(32)),3);
        var snapshot=List.of(entry(child,1000,0),entry(independent,400,0),entry(parent,0,0));
        long budget=TransactionWeight.calculate(parent)+TransactionWeight.calculate(child);
        assertEquals(List.of(parent,child),TransactionSelector.select(snapshot,budget,100,new FeeRate(1000)));
        assertEquals(List.of(independent),TransactionSelector.select(snapshot,budget-1,100,new FeeRate(1000)));
    }
    @Test void sigopsBudgetAndAlreadyChosenParentAreAccountedOnce() {
        var parent=tx(Hash256.fromDisplayHex("11".repeat(32)),1); var child=tx(parent.txId(),2);
        var snapshot=List.of(entry(child,100,2),entry(parent,1000,2));
        long budget=TransactionWeight.calculate(parent)+TransactionWeight.calculate(child);
        assertEquals(List.of(parent),TransactionSelector.select(snapshot,budget,3,new FeeRate(0)));
        assertEquals(List.of(parent,child),TransactionSelector.select(snapshot,budget,4,new FeeRate(0)));
        assertTrue(TransactionSelector.select(snapshot,0,100,new FeeRate(0)).isEmpty());
    }
}
