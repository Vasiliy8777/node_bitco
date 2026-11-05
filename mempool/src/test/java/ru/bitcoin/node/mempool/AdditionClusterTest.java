package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AdditionClusterTest {
    @Test void spentIndexTraversalMatchesWholeGraphAcrossMergesAndRestoredParents() {
        var random = new Random(0x32ad17);
        for (int trial = 0; trial < 400; trial++) {
            var candidate = new LinkedHashMap<Hash256, MempoolEntry>();
            var vertices = new ArrayList<MempoolEntry>(); var usedOutputs = new int[48];
            int count = 8 + random.nextInt(32);
            for (int i = 0; i < count; i++) {
                var inputs = new ArrayList<TxIn>(); var parents = new TreeSet<Integer>();
                if (i > 0) for (int j = 0; j < random.nextInt(4); j++) parents.add(random.nextInt(i));
                for (int parent : parents) inputs.add(new TxIn(new OutPoint(vertices.get(parent).transaction().txId(),
                        new UInt32(usedOutputs[parent]++)), new byte[0], TxIn.FINAL_SEQUENCE));
                if (inputs.isEmpty()) inputs.add(new TxIn(new OutPoint(Hash256.fromDisplayHex(String.format("%064x", trial * 100 + i + 1)),
                        new UInt32(0)), new byte[0], TxIn.FINAL_SEQUENCE));
                var outputs = new ArrayList<TxOut>();
                for (int output = 0; output < 48; output++) outputs.add(new TxOut(1000 + i, new byte[]{0x51}));
                var tx = new Transaction(random.nextInt(4) == 0 ? 3 : 2, inputs, outputs, new UInt32(trial));
                var entry = new MempoolEntry(tx, 1000, TransactionWeight.calculate(tx), 123);
                candidate.put(tx.txId(), entry); vertices.add(entry);
            }
            // Removing an arbitrary vertex also exercises restoring a parent
            // when already admitted children spend its confirmed outputs.
            var added = vertices.get(random.nextInt(count)); var root = added.transaction().txId();
            var current = new LinkedHashMap<>(candidate); current.remove(root);
            var spent = new HashMap<OutPoint, Hash256>();
            current.forEach((id, entry) -> entry.transaction().inputs().forEach(input -> assertNull(spent.put(input.previousOutput(), id))));
            var affected = MempoolGraphPolicy.additionCluster(current, spent, added, Integer.MAX_VALUE);
            assertEquals(ClusterLinearization.connected(candidate, List.of(root)), affected.keySet(), "trial " + trial);
            var limits = new MempoolLimits(1 + random.nextInt(40), 500 + random.nextInt(20_000), 300_000_000, 1209600, 100);
            String incremental;
            try {
                incremental = rejection(MempoolGraphPolicy.additionCluster(current, spent, added, limits.clusterCount()), root, limits);
            } catch (MempoolAdmissionException rejected) { incremental = rejected.getMessage(); }
            assertEquals(rejection(candidate, root, limits), incremental, "policy trial " + trial);
        }
    }
    private static String rejection(Map<Hash256, MempoolEntry> entries, Hash256 root, MempoolLimits limits) {
        try { MempoolGraphPolicy.checkLimits(entries, root, limits); return null; }
        catch (MempoolAdmissionException rejected) { return rejected.getMessage(); }
    }
}
