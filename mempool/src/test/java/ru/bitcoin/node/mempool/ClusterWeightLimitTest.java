package ru.bitcoin.node.mempool;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ClusterWeightLimitTest {
    @ParameterizedTest
    @CsvSource({"401,401,201,true", "401,402,201,true", "403,402,201,false", "400,400,200,true"})
    void clusterLimitUsesAggregateAdjustedWeight(long parentWeight, long childWeight, long limit, boolean allowed) {
        var parent = tx(Hash256.fromDisplayHex("11".repeat(32)), 1);
        var child = tx(parent.txId(), 2);
        var entries = new LinkedHashMap<Hash256, MempoolEntry>();
        entries.put(parent.txId(), new MempoolEntry(parent, 100, parentWeight, 0));
        entries.put(child.txId(), new MempoolEntry(child, 100, childWeight, 0));
        var limits = new MempoolLimits(64, limit, 300_000_000, 1209600, 100);
        if (allowed) assertDoesNotThrow(() -> MempoolGraphPolicy.checkLimits(entries, child.txId(), limits));
        else assertEquals("cluster-size-limit", assertThrows(MempoolAdmissionException.class,
                () -> MempoolGraphPolicy.checkLimits(entries, child.txId(), limits)).getMessage());
    }
    private static Transaction tx(Hash256 parent, int tag) {
        return new Transaction(2, List.of(new TxIn(new OutPoint(parent, new UInt32(0)), new byte[0], TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(tag, new byte[]{0x51})), new UInt32(0));
    }
}
