package ru.bitcoin.node.mempool;

import org.junit.jupiter.api.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;

class ClusterLimitTrimmerTest {
    @TestFactory Stream<DynamicTest> nativeOversizedReorgGroups() throws Exception {
        var scenarios=TxGraphReferenceModel.scenarios(lines("trim-operations.txt"));
        var answers=TxGraphReferenceModel.answers(lines("trim-removed.tsv"));
        return java.util.stream.IntStream.range(0,scenarios.size()).mapToObj(i -> DynamicTest.dynamicTest("Core Trim "+i,()->replay(scenarios.get(i),answers)));
    }
    private void replay(List<String> operations,Map<Integer,String> answers) {
        var entries=new LinkedHashMap<Hash256,MempoolEntry>();
        var dependencies=new ArrayList<ClusterLimitTrimmer.Dependency>();
        List<List<ClusterLinearization.Chunk>> orders=List.of();
        int limit=0; long weight=0;
        for (var operation : operations) {
            var fields=operation.split(" +");
            switch(fields[0]) {
                case "new" -> {limit=Integer.parseInt(fields[1]);weight=Long.parseLong(fields[2]);}
                case "add" -> {
                    var id=key(Integer.parseInt(fields[1]));
                    var inputs=new ArrayList<TxIn>();
                    for(int j=0;j<Integer.parseInt(fields[4]);j++) inputs.add(new TxIn(new OutPoint(key(Integer.parseInt(fields[5+j])),new UInt32(0)),new byte[0],TxIn.FINAL_SEQUENCE));
                    var tx=new Transaction(2,inputs,List.of(new TxOut(1000,new byte[]{0x51})),new UInt32(0));
                    entries.put(id,new MempoolEntry(tx,Long.parseLong(fields[2]),Long.parseLong(fields[3]),0));
                }
                case "work" -> orders=ClusterLinearization.clusters(entries).stream().map(group -> ClusterLinearization.chunks(entries,group)).toList();
                case "dep" -> dependencies.add(new ClusterLimitTrimmer.Dependency(key(Integer.parseInt(fields[1])),key(Integer.parseInt(fields[2]))));
                case "trim" -> {
                    var removed=ClusterLimitTrimmer.removed(entries,orders,dependencies,limit,weight).stream()
                            .map(id -> Byte.toUnsignedInt(id.bytes()[0])).sorted().map(Object::toString).toList();
                    assertEquals(answers.get(Integer.parseInt(fields[1])),fields[1]+"\t"+String.join(",",removed));
                }
                case "check" -> { }
                default -> throw new IllegalArgumentException(operation);
            }
        }
    }
    private static Hash256 key(int id) {var bytes=new byte[32];bytes[0]=(byte)id;return new Hash256(bytes);}
    private List<String> lines(String file) throws Exception {
        try(var input=getClass().getResourceAsStream("/core32-reference/"+file)) {
            assertNotNull(input);return new String(input.readAllBytes(),StandardCharsets.UTF_8).lines().toList();
        }
    }
}
