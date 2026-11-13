package ru.bitcoin.node.mempool;

import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

/** Replay genuine Core TxGraph snapshots without calling the implementation as an oracle. */
final class TxGraphReferenceModel {
    private static final class State {
        final MempoolGraphIndex graph = new MempoolGraphIndex();
        final Map<Hash256, MempoolEntry> entries = new LinkedHashMap<>();
        State fork() { var result = new State(); result.entries.putAll(entries); result.graph.copyFrom(graph); return result; }
        void fee(Hash256 id, long value) {
            var original = entries.get(id);
            if (original != null) { entries.put(id, original.withFeeDelta(value - original.fee())); graph.entryChanged(id); }
        }
    }
    static List<List<String>> scenarios(List<String> lines) {
        var result = new ArrayList<List<String>>(); List<String> current = null;
        for (var line : lines) {
            if (line.startsWith("#") || line.isBlank()) continue;
            if (line.equals("new") || line.startsWith("new ")) { current = new ArrayList<>(); result.add(current); }
            Objects.requireNonNull(current).add(line);
        }
        return result;
    }
    static Map<Integer, String> answers(List<String> rows) {
        var result = new HashMap<Integer,String>();
        for (var row : rows) if (!row.startsWith("#")) result.put(Integer.parseInt(row.split("\t",2)[0]),row);
        return Map.copyOf(result);
    }
    static void replay(List<String> operations, Map<Integer,String> expected) {
        State main = null, stage = null;
        for (var operation : operations) {
            var fields = operation.trim().split(" +"); var top = stage == null ? main : stage;
            switch (fields[0]) {
                case "new" -> { main = new State(); stage = null; }
                case "start" -> stage = Objects.requireNonNull(main).fork();
                case "abort" -> stage = null;
                case "commit" -> { main = Objects.requireNonNull(stage); stage = null; }
                case "add" -> {
                    var id = key(Integer.parseInt(fields[1])); long fee = Long.parseLong(fields[2]), weight = Long.parseLong(fields[3]);
                    int count = Integer.parseInt(fields[4]); var parents = new LinkedHashSet<Hash256>(); var inputs = new ArrayList<TxIn>();
                    for (int i=0;i<count;i++) {
                        var parent = key(Integer.parseInt(fields[5+i])); parents.add(parent);
                        inputs.add(new TxIn(new OutPoint(parent,new UInt32(0)),new byte[0],TxIn.FINAL_SEQUENCE));
                    }
                    var tx = new Transaction(2,inputs,List.of(new TxOut(1000,new byte[]{0x51})),new UInt32(0));
                    Objects.requireNonNull(top).entries.put(id,new MempoolEntry(tx,fee,weight,0));
                    top.graph.add(id,parents,Set.of());
                }
                case "remove" -> {
                    var id = key(Integer.parseInt(fields[1])); Objects.requireNonNull(top).entries.remove(id); top.graph.removeAll(Set.of(id));
                }
                case "fee" -> {
                    var id = key(Integer.parseInt(fields[1])); long fee = Long.parseLong(fields[2]);
                    Objects.requireNonNull(main).fee(id,fee); if (stage != null) stage.fee(id,fee);
                }
                case "work" -> {
                    long budget = Long.parseLong(fields[1]); if (stage != null) stage.graph.doWork(stage.entries,budget);
                    Objects.requireNonNull(main).graph.doWork(main.entries,budget);
                }
                case "query" -> {
                    int sequence = Integer.parseInt(fields[1]);
                    if (stage != null) stage.graph.doWork(stage.entries,1_000_000_000L);
                    Objects.requireNonNull(main).graph.doWork(main.entries,1_000_000_000L);
                    var chunks = ClusterLinearization.mergeChunks(main.graph.linearizations(main.entries));
                    var chunkText = chunks.stream().map(chunk -> chunk.fee()+","+chunk.adjustedWeight()+":"+ordered(chunk.transactions())).collect(Collectors.joining(";"));
                    var worst = chunks.isEmpty() ? "0,0:" : chunks.getLast().fee()+","+chunks.getLast().adjustedWeight()+":"+ordered(chunks.getLast().transactions().reversed());
                    var actual = sequence+"\t"+(main.graph.optimal() && (stage == null || stage.graph.optimal()) ? "1" : "0")+"\t"+chunkText+"\t"+relations(main)+"\t"+relations(stage == null ? main : stage)+"\t"+worst;
                    if (expected.get(sequence).split("\t", -1).length == 7)
                        actual += "\t" + main.graph.memoryUsage(main.entries);
                    if (!actual.equals(expected.get(sequence))) throw new AssertionError("Core TxGraph snapshot " + sequence+"\nEXPECTED "+expected.get(sequence)+"\nACTUAL   "+actual);
                }
                case "diagram" -> {
                    var roots=new LinkedHashSet<Hash256>();
                    roots.addAll(Objects.requireNonNull(main).graph.component(key(Integer.parseInt(fields[2]))));
                    var old=Mempool.affectedChunks(main.graph,main.entries,roots);
                    roots.add(key(Integer.parseInt(fields[3])));
                    var next=Mempool.affectedChunks(Objects.requireNonNull(stage).graph,stage.entries,roots);
                    String actual=fields[1]+"\t"+curve(old)+"\t"+curve(next);
                    if(!actual.equals(expected.get(Integer.parseInt(fields[1]))))
                        throw new AssertionError("Core diagrams "+fields[1]+"\nEXPECTED "+expected.get(Integer.parseInt(fields[1]))+"\nACTUAL "+actual);
                }
                default -> throw new IllegalArgumentException(operation);
            }
        }
    }
    private static String relations(State state) {
        return state.entries.keySet().stream().sorted(Comparator.comparingInt(TxGraphReferenceModel::id)).map(key -> id(key)+":"+
                sorted(state.graph.ancestors(key))+"/"+sorted(state.graph.descendants(key))+"/"+sorted(state.graph.component(key))).collect(Collectors.joining(";"));
    }
    private static String curve(List<ClusterLinearization.Chunk> chunks) {
        return chunks.stream().sorted((a,b)-> {
            int rate=ClusterLinearization.compareRate(b.fee(),b.adjustedWeight(),a.fee(),a.adjustedWeight());
            return rate!=0?rate:Long.compare(a.adjustedWeight(),b.adjustedWeight());
        }).map(chunk->chunk.fee()+","+chunk.adjustedWeight()).collect(Collectors.joining(";"));
    }
    private static String sorted(Collection<Hash256> ids) { return ordered(ids.stream().sorted(Comparator.comparingInt(TxGraphReferenceModel::id)).toList()); }
    private static String ordered(Collection<Hash256> ids) { return ids.stream().map(TxGraphReferenceModel::id).map(Object::toString).collect(Collectors.joining(",")); }
    private static int id(Hash256 id) { return Byte.toUnsignedInt(id.bytes()[0]); }
    private static Hash256 key(int id) { var bytes = new byte[32]; bytes[0]=(byte)id; return new Hash256(bytes); }
    public static void main(String[] args) throws Exception {
        var root = Path.of("mempool/src/test/resources/core32-reference");
        var operations = scenarios(Files.readAllLines(root.resolve("txgraph-operations.txt")));
        var answers = answers(Files.readAllLines(root.resolve("txgraph-snapshots.tsv")));
        for (var scenario : operations) replay(scenario,answers);
        System.out.println("CORE_TXGRAPH scenarios="+operations.size()+" snapshots="+answers.size()+" failures=0");
    }
}
