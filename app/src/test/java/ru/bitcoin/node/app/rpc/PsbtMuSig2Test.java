package ru.bitcoin.node.app.rpc;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PsbtMuSig2Test {
    @Test void matchesIndependentReferenceSignaturesAndNativeCoreFinalization() throws Exception {
        List<?> vectors;
        try (var stream = getClass().getResourceAsStream("/rpc/musig-finalizer.json")) {
            assertNotNull(stream); vectors = JsonMapper.builder().build().readValue(stream,List.class);
        }
        assertEquals(72,vectors.size());
        for (Object value : vectors) {
            var vector = (Map<?,?>)value;
            String psbt = vector.get("psbt").toString(), label = vector.get("label").toString();
            assertEquals(vector.get("finalized"),PsbtFinalizer.finish(psbt,false),label);
            assertEquals(vector.get("extracted"),PsbtFinalizer.finish(psbt,true),label);
        }
    }
}
