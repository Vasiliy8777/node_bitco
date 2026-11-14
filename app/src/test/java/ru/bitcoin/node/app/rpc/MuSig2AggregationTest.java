package ru.bitcoin.node.app.rpc;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MuSig2AggregationTest {
    private static final HexFormat HEX = HexFormat.of();
    @Test void officialBip327AggregationAndCorruptedContributions() throws Exception {
        Map<?,?> vectors;
        try (var stream = getClass().getResourceAsStream("/rpc/bip327-sig-agg.json")) {
            assertNotNull(stream); vectors = JsonMapper.builder().build().readValue(stream,Map.class);
        }
        for (String group : List.of("valid_test_cases","error_test_cases")) for (Object value : (List<?>)vectors.get(group)) {
            var test = (Map<?,?>)value;
            var keys = selected(vectors,test,"pubkeys","key_indices");
            var nonces = selected(vectors,test,"pnonces","nonce_indices");
            var partials = selected(vectors,test,"psigs","psig_indices");
            var scalars = selected(vectors,test,"tweaks","tweak_indices");
            var tweaks = new ArrayList<MuSig2Aggregation.Tweak>();
            for (int i=0;i<scalars.size();i++) tweaks.add(new MuSig2Aggregation.Tweak(scalars.get(i),(Boolean)((List<?>)test.get("is_xonly")).get(i)));
            byte[] aggregate = HEX.parseHex(test.get("aggregate_pubkey").toString());
            assertArrayEquals(aggregate,MuSig2Aggregation.publicKey(keys));
            byte[] message = HEX.parseHex(vectors.get("msg").toString());
            byte[] actual = MuSig2Aggregation.aggregate(keys,aggregate,tweaks,message,nonces,partials);
            if (group.equals("error_test_cases")) { assertNull(actual); continue; }
            assertArrayEquals(HEX.parseHex(test.get("expected").toString()),actual);
            for (int signer=0;signer<partials.size();signer++) for (int position=0;position<32;position++) {
                var corrupted = new ArrayList<>(partials); byte[] changed = partials.get(signer).clone(); changed[position] ^= 1; corrupted.set(signer,changed);
                assertNull(MuSig2Aggregation.aggregate(keys,aggregate,tweaks,message,nonces,corrupted));
            }
            byte[] changedMessage = message.clone(); changedMessage[0] ^= 1;
            assertNull(MuSig2Aggregation.aggregate(keys,aggregate,tweaks,changedMessage,nonces,partials));
            byte[] wrongAggregate = aggregate.clone(); wrongAggregate[0] ^= 1;
            assertNull(MuSig2Aggregation.aggregate(keys,wrongAggregate,tweaks,message,nonces,partials));
            for (int signer=0;signer<nonces.size();signer++) {
                var corrupted = new ArrayList<>(nonces); corrupted.set(signer,new byte[66]);
                assertNull(MuSig2Aggregation.aggregate(keys,aggregate,tweaks,message,corrupted,partials));
            }
        }
    }
    private static List<byte[]> selected(Map<?,?> vectors,Map<?,?> test,String source,String indices) {
        var result = new ArrayList<byte[]>(); var values = (List<?>)vectors.get(source);
        for (Object index : (List<?>)test.get(indices)) result.add(HEX.parseHex(values.get(((Number)index).intValue()).toString()));
        return result;
    }
}
