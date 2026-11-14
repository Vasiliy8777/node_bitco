package ru.bitcoin.node.app.rpc;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MiniscriptTextTest {
    private static final byte[] KEY=HexFormat.of().parseHex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798");
    @Test void rejectsInvalidGrammarAndTypeCombinations() {
        for(String expression:List.of("","pk()","pk(key))","pk(key),pk(other)","after(-1)","after(+1)","after(0)","older(2147483648)",
                "or_i(pk(key))","or_i(pk(key),pk(other),pk(third))","thresh(0,pk(key))",
                "thresh(2,pk(key))","multi(0,key)","multi(2,key)","q:pk(key)","v:","sha256(00)","pk(key) "))
            assertNull(MiniscriptText.parse(expression,false,key->key.isEmpty()?null:key),expression);
        var invalidType=MiniscriptText.parse("and_v(pk(key),pk(other))",false,key->key.isEmpty()?null:key);assertNotNull(invalidType);assertFalse(invalidType.valid());
        assertNull(MiniscriptText.parse("multi(1,key)",true,key->key.isEmpty()?null:key));
        assertNull(MiniscriptText.parse("multi_a(1,key)",false,key->key.isEmpty()?null:key));
    }
    @Test void deeplyNestedTextAndScriptEncodersDoNotUseTheJavaCallStack() {
        for(boolean taproot:List.of(false,true)) {
            String expression="n".repeat(2000)+":pk(key)";
            var parsed=MiniscriptText.parse(expression,taproot,key->key.isEmpty()?null:key);assertNotNull(parsed);
            var context=context(taproot);assertEquals(expression,MiniscriptEncoding.text(parsed,context));
            assertEquals(parsed.scriptSize,MiniscriptEncoding.script(parsed,context).length);
            assertEquals(taproot,MiniscriptLimits.analyze(parsed,Comparator.naturalOrder()).sane());
        }
        String nested="and_v(v:pk(key),".repeat(1000)+"pk(other)"+")".repeat(1000);
        var parsed=MiniscriptText.parse(nested,true,key->key.isEmpty()?null:key);assertNotNull(parsed);
        assertEquals(parsed.scriptSize,MiniscriptEncoding.script(parsed,context(true)).length);
        assertFalse(MiniscriptLimits.analyze(parsed,Comparator.naturalOrder()).sane());
        assertNull(MiniscriptText.parse(nested,false,key->key.isEmpty()?null:key));
    }
    private static Miniscript.Context<String> context(boolean taproot) {
        return new Miniscript.Context<>() {
            public boolean taproot(){return taproot;}
            public String fromPublicKey(byte[] bytes){return "key";}
            public String fromKeyHash(byte[] bytes){return "key";}
            public byte[] publicKey(String key){return taproot?Arrays.copyOfRange(KEY,1,33):KEY.clone();}
            public String keyText(String key){return key;}
        };
    }
}
