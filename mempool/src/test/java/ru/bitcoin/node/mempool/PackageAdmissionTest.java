package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.crypto.hash.Sha256;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PackageAdmissionTest {
    static final byte[] SCRIPT=HexFormat.of().parseHex("0020"+HexFormat.of().formatHex(Sha256.hash(new byte[]{0x51})));
    static final OutPoint FUND=new OutPoint(Hash256.fromDisplayHex("11".repeat(32)),new UInt32(0));
    static final UtxoView COINS=point -> point.equals(FUND)?Optional.of(new UtxoEntry(100_000,SCRIPT,100,false)):Optional.empty();
    static final MempoolValidationContext CONTEXT=new MempoolValidationContext(200,1_700_000_000,h -> 1_600_000_000);

    @Test void childPaysForZeroFeeParentAndPackageSurvivesRevalidation() {
        var pool=new Mempool(); var parent=tx(List.of(FUND),2,100_000); var child=tx(List.of(point(parent,0)),2,90_000);
        assertThrows(MempoolAdmissionException.class,()->pool.admit(parent,CONTEXT,COINS));
        assertEquals(2,pool.admitPackage(List.of(parent,child),CONTEXT,COINS).size());
        assertEquals(List.of(),pool.revalidate(CONTEXT,COINS,Set.of()));
        assertEquals(2,pool.size());
    }
    @Test void invalidChildDoesNotInsertParent() {
        var pool=new Mempool(); var parent=tx(List.of(FUND),2,100_000); var child=tx(List.of(point(parent,0)),2,90_000);
        var bad=new Transaction(2,List.of(new TxIn(point(parent,0),new byte[0],TxIn.FINAL_SEQUENCE,new Witness(List.of(new byte[]{0})))),child.outputs(),new UInt32(0));
        assertThrows(TransactionValidationException.class,()->pool.admitPackage(List.of(parent,bad),CONTEXT,COINS));
        assertTrue(pool.isEmpty());
    }
    @Test void packageCannotDoubleCountKnownParentFee() {
        var pool=new Mempool(); var parent=tx(List.of(FUND),2,90_000); var child=tx(List.of(point(parent,0)),2,90_000);
        pool.admit(parent,CONTEXT,COINS);
        assertThrows(MempoolAdmissionException.class,()->pool.admitPackage(List.of(parent,child),CONTEXT,COINS));
        assertEquals(1,pool.size());
    }
    @Test void freshHighFeeParentCannotPayForLowFeeChild() {
        var pool=new Mempool(); var parent=tx(List.of(FUND),2,90_000); var child=tx(List.of(point(parent,0)),2,90_000);
        assertThrows(MempoolAdmissionException.class,()->pool.admitPackage(List.of(parent,child),CONTEXT,COINS));
        assertTrue(pool.isEmpty());
    }
    @Test void rejectUnsortedDuplicateConflictingAndDisconnectedPackages() {
        var pool=new Mempool(); var parent=tx(List.of(FUND),2,100_000); var child=tx(List.of(point(parent,0)),2,90_000);
        for(var pack:List.of(List.of(child,parent),List.of(parent,parent),List.of(parent,tx(List.of(FUND),2,90_000)),
                List.of(parent,tx(List.of(new OutPoint(Hash256.fromDisplayHex("22".repeat(32)),new UInt32(0))),2,1000)))) {
            assertThrows(MempoolAdmissionException.class,()->pool.admitPackage(pack,CONTEXT,COINS));
        }
        assertTrue(pool.isEmpty());
    }
    @Test void ephemeralDustMustBeSpentByEveryChildSpendingItsParent() {
        var pool=new Mempool(); var parent=tx(List.of(FUND),2,100_000,0);
        var missing=tx(List.of(point(parent,0)),2,90_000);
        assertThrows(MempoolAdmissionException.class,()->pool.admitPackage(List.of(parent,missing),CONTEXT,COINS));
        var child=tx(List.of(point(parent,0),point(parent,1)),2,90_000);
        pool.admitPackage(List.of(parent,child),CONTEXT,COINS);
        pool.remove(child.txId());
        assertThrows(MempoolAdmissionException.class,()->pool.admit(missing,CONTEXT,COINS));
    }
    @Test void nonzeroFeeEphemeralParentIsRejected() {
        var pool=new Mempool(); var parent=tx(List.of(FUND),2,99_999,0); var child=tx(List.of(point(parent,0),point(parent,1)),2,90_000);
        assertThrows(MempoolAdmissionException.class,()->pool.admitPackage(List.of(parent,child),CONTEXT,COINS));
        assertTrue(pool.isEmpty());
    }
    @Test void packageRbfMustImproveDiagramNotOnlyTotalFee() {
        var pool=new Mempool(); var original=tx(List.of(FUND),2,90_000); pool.admit(original,CONTEXT,COINS);
        var parent=tx(List.of(FUND),2,100_000); var weak=tx(List.of(point(parent,0)),2,89_000);
        assertThrows(MempoolAdmissionException.class,()->pool.admitPackage(List.of(parent,weak),CONTEXT,COINS));
        assertTrue(pool.contains(original.txId()));
        var strong=tx(List.of(point(parent,0)),2,70_000);
        pool.admitPackage(List.of(parent,strong),CONTEXT,COINS);
        assertFalse(pool.contains(original.txId())); assertEquals(2,pool.size());
    }
    @Test void repeatedPackageReturnsKnownEntries() {
        var pool=new Mempool(); var parent=tx(List.of(FUND),2,100_000); var child=tx(List.of(point(parent,0)),2,90_000);
        var pack=List.of(parent,child); var first=pool.admitPackage(pack,CONTEXT,COINS);
        assertEquals(first,pool.admitPackage(pack,CONTEXT,COINS)); assertEquals(2,pool.size());
    }
    static OutPoint point(Transaction tx,int i) { return new OutPoint(tx.txId(),new UInt32(i)); }
    static Transaction tx(List<OutPoint> points,int version,long... values) {
        return new Transaction(version,points.stream().map(point -> new TxIn(point,new byte[0],TxIn.FINAL_SEQUENCE,new Witness(List.of(new byte[]{0x51})))).toList(),
                Arrays.stream(values).mapToObj(value -> new TxOut(value,SCRIPT)).toList(),new UInt32(0));
    }
}
