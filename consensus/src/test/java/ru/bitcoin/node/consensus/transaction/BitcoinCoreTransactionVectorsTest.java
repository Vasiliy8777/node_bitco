package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.protocol.serialization.TransactionParser;
import ru.bitcoin.node.script.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class BitcoinCoreTransactionVectorsTest {
    @TestFactory Stream<DynamicTest> validTransactions() throws Exception { return vectors("tx_valid",120,true); }
    @TestFactory Stream<DynamicTest> invalidTransactions() throws Exception { return vectors("tx_invalid",93,false); }
    private Stream<DynamicTest> vectors(String name,int count,boolean valid) throws Exception {
        List<String> rows;
        try(var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/bitcoin-core-v30/"+name+".txt")),StandardCharsets.UTF_8))) {
            rows=reader.lines().toList();
        }
        assertEquals(count,rows.size());
        int all=0;
        for(var field:ScriptVerifyFlags.class.getFields()) if(field.getType()==int.class) all |= field.getInt(null);
        final int allFlags=all;
        return IntStream.range(0,rows.size()).mapToObj(i -> DynamicTest.dynamicTest(name+" #"+(i+1),()->{
            var fields=rows.get(i).split("\\|",-1);
            Transaction tx;
            try { tx=TransactionParser.parse(HexFormat.of().parseHex(fields[0])); }
            catch (IllegalArgumentException malformed) {
                assertFalse(valid);
                assertEquals("BADTX",fields[1]);
                return;
            }
            if(fields[1].equals("BADTX")) {
                assertThrows(TransactionValidationException.class,()->TransactionValidator.validateBasic(tx));
                return;
            }
            TransactionValidator.validateBasic(tx);
            Map<OutPoint,UtxoEntry> coins=new HashMap<>();
            for(int j=2;j<fields.length;j++) {
                var coin=fields[j].split(",",-1);
                long index=Long.parseLong(coin[1]);
                coins.put(new OutPoint(Hash256.fromDisplayHex(coin[0]),new UInt32(index & 0xffffffffL)),
                        new UtxoEntry(Long.parseLong(coin[2]),BitcoinCoreScriptVectorsTest.assemble(
                                new String(Base64.getDecoder().decode(coin[3]),StandardCharsets.UTF_8)),1,false));
            }
            int listed=ScriptVerifyFlags.parseNames(fields[1]);
            int flags=valid ? allFlags & ~listed : listed;
            boolean accepted;
            try {
                if (tx.isCoinbase()) {
                    var input=tx.inputs().getFirst();
                    var coin=Objects.requireNonNull(coins.get(input.previousOutput()));
                    accepted=LegacyScriptVerifier.verify(tx,0,input.scriptSig(),coin.scriptPubKey(),flags);
                } else {
                    InputScriptValidator.validateAll(tx,p -> Optional.ofNullable(coins.get(p)),flags);
                    accepted=true;
                }
            } catch(TransactionValidationException | ScriptExecutionException | ScriptParseException expected) {
                accepted=false;
            }
            assertEquals(valid,accepted,"flags="+fields[1]);
        }));
    }
}
