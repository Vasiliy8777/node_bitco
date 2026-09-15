package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class ExtendedMempoolPolicyTest {
    @Test void reconciliationTrimsResurrectedTransactionsToCapacity() {
        var pool = new Mempool(new MempoolPolicy(),
                new MempoolLimits(25,25,101_000,1,1000,100),Clock.systemUTC());
        var transaction = tx(List.of(FUND),2,90_000);
        pool.reconcile(CONTEXT,COINS,Set.of(),List.of(transaction));
        assertTrue(pool.isEmpty());
        assertTrue(pool.minimumFeeRate() > 100);
    }
    @Test void trucSiblingCanReplaceDifferentOutputSpendWithHigherFee() {
        var pool=new Mempool(); var parent=tx(List.of(FUND),3,45_000,45_000);
        var first=tx(List.of(point(parent,0)),3,40_000); var sibling=tx(List.of(point(parent,1)),3,30_000);
        pool.admit(parent,CONTEXT,COINS); pool.admit(first,CONTEXT,COINS);
        pool.admit(sibling,CONTEXT,COINS);
        assertFalse(pool.contains(first.txId())); assertTrue(pool.contains(sibling.txId())); assertEquals(2,pool.size());
    }
    @Test void cpfpCarveoutAllowsOneExtraChildButNotTwo() {
        var pool=new Mempool(new MempoolPolicy(),new MempoolLimits(25,2,101_000,1_000_000,1000,100),Clock.systemUTC());
        var parent=tx(List.of(FUND),2,30_000,30_000,30_000);
        pool.admit(parent,CONTEXT,COINS);
        pool.admit(tx(List.of(point(parent,0)),2,20_000),CONTEXT,COINS);
        pool.admit(tx(List.of(point(parent,1)),2,20_000),CONTEXT,COINS);
        assertThrows(MempoolAdmissionException.class,()->pool.admit(tx(List.of(point(parent,2)),2,20_000),CONTEXT,COINS));
        assertEquals(3,pool.size());
    }
    @Test void rollingFeeWaitsForBlockThenDecaysAndResets() {
        var fee=new RollingMinimumFee(); fee.bump(1000,100,100);
        assertEquals(1100,fee.get(100_000,0,1000,100));
        fee.blockConnected(100_000);
        assertEquals(550,fee.get(110_800,0,1000,100));
        assertEquals(0,fee.get(200_000,0,1000,100));
    }
    @Test void rollingFeeHasSlowerDecayForFullPool() {
        var fee=new RollingMinimumFee(); fee.bump(1000,100,0); fee.blockConnected(1);
        assertEquals(550,fee.get(43_201,900,1000,100));
    }
    @Test void customDustAndDataCarrierSettingsAreApplied() {
        var strict=new MempoolPolicy(new FeeRate(100),0,6000);
        var transaction=tx(List.of(FUND),2,400);
        assertEquals(660,ru.bitcoin.node.mempool.policy.StandardTransactionPolicy.dustThreshold(transaction.outputs().getFirst(),6000));
        assertThrows(MempoolAdmissionException.class,()->new Mempool(strict).admit(transaction,CONTEXT,COINS));
    }
}
