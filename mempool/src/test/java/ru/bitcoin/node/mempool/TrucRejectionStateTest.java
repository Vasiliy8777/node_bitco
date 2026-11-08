package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static ru.bitcoin.node.mempool.PackageAdmissionTest.*;

class TrucRejectionStateTest {
    @Test void bothInheritanceDirectionsReturnIndexedCoreState() {
        for(int parentVersion:List.of(2,3)) {
            var parent=tx(List.of(FUND),parentVersion,99000);var child=tx(List.of(point(parent,0)),parentVersion==3?2:3,98000);
            var pool=new Mempool();pool.admit(parent,CONTEXT,COINS);var before=pool.snapshot();
            var result=pool.testAccept(List.of(child),CONTEXT,COINS);
            assertEquals("TRUC-violation",result.rejectReason());assertEquals(0,result.failedTransactionIndex());assertFalse(result.packagePolicyFailure());
            assertTrue(result.rejectDetails().contains(child.txId().toDisplayHex()));assertTrue(result.rejectDetails().contains(parent.wtxId().toDisplayHex()));
            assertEquals(result.rejectDetails(),pool.submitPackage(List.of(child),CONTEXT,COINS,0).transactions().get(child.wtxId()).error());
            assertEquals(before,pool.snapshot());
        }
    }
    @Test void inheritancePrecedesInsufficientReplacementFee() {
        var pool=new Mempool();var parent=tx(List.of(FUND),3,99000);pool.admit(parent,CONTEXT,COINS);
        var oldChild=tx(List.of(point(parent,0)),3,98000);pool.admit(oldChild,CONTEXT,COINS);
        var invalidReplacement=tx(List.of(point(parent,0)),2,98000);var before=pool.snapshot();
        var result=pool.testAccept(List.of(invalidReplacement),CONTEXT,COINS);
        assertEquals("TRUC-violation",result.rejectReason());assertTrue(result.rejectDetails().contains("cannot spend from version=3"));
        assertEquals(before,pool.snapshot());
    }
    @Test void sharedInheritanceIsPackageWideAndPreservesIndividualFeeResults() {
        var pool=new Mempool();var parent=tx(List.of(FUND),3,100000);var child=tx(List.of(point(parent,0)),2,90000);
        var result=pool.submitPackage(List.of(parent,child),CONTEXT,COINS,0);
        assertTrue(result.packageMessage().startsWith("TRUC-violation, non-version=3 tx "));
        assertTrue(result.transactions().get(parent.wtxId()).error().startsWith("min relay fee not met"));
        assertEquals("bad-txns-inputs-missingorspent",result.transactions().get(child.wtxId()).error());assertTrue(pool.isEmpty());
    }
}
