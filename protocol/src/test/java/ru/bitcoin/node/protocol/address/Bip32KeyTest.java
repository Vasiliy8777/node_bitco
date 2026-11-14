package ru.bitcoin.node.protocol.address;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class Bip32KeyTest {
    @Test void officialDerivationAndSerializationVectors() throws Exception {
        for(String row:resource("valid.tsv")) {
            String[] fields=row.split("\t");
            Bip32Key derived=Bip32Key.fromSeed(HexFormat.of().parseHex(fields[0]),true);
            String[] path=fields[1].split("/");
            for(int i=1;i<path.length;i++) {
                String component=path[i];boolean hardened=component.endsWith("'");
                long index=Long.parseLong(hardened?component.substring(0,component.length()-1):component)+(hardened?0x8000_0000L:0);
                if(hardened)assertTrue(derived.neuter().derive(index).isEmpty());
                else assertEquals(derived.derive(index).orElseThrow().neuter().encode(),derived.neuter().derive(index).orElseThrow().encode());
                derived=derived.derive(index).orElseThrow();
            }
            assertEquals(fields[2],derived.neuter().encode(),fields[1]);assertEquals(fields[3],derived.encode(),fields[1]);
            assertEquals(fields[2],Bip32Key.decode(fields[2],NetworkParametersRegistry.mainnet()).encode());
            assertEquals(fields[3],Bip32Key.decode(fields[3],NetworkParametersRegistry.mainnet()).encode());
            assertEquals(fields[2],Bip32Key.decode(fields[3],NetworkParametersRegistry.mainnet()).neuter().encode());
            assertThrows(IllegalArgumentException.class,()->Bip32Key.decode(fields[2],NetworkParametersRegistry.regtest()));
        }
    }
    @Test void officialInvalidKeys() throws Exception {
        for(String encoded:resource("invalid.txt"))assertThrows(IllegalArgumentException.class,()->Bip32Key.decode(encoded,NetworkParametersRegistry.mainnet()));
    }
    @Test void depthAndChildBoundsAndNetworkPrefixes() {
        Bip32Key key=Bip32Key.fromSeed(new byte[16],false);
        assertTrue(key.encode().startsWith("tprv"));assertTrue(key.neuter().encode().startsWith("tpub"));
        assertEquals(key.encode(),Bip32Key.decode(key.encode(),NetworkParametersRegistry.regtest()).encode());
        assertTrue(key.derive(-1).isEmpty());assertTrue(key.derive(0x1_0000_0000L).isEmpty());
        for(int i=0;i<255;i++)key=key.derive(0).orElseThrow();
        assertEquals(255,key.depth());assertTrue(key.derive(0).isEmpty());assertTrue(key.neuter().derive(0).isEmpty());
    }
    private List<String> resource(String name) throws Exception {
        try(var stream=getClass().getResourceAsStream("/bip32/"+name)) {
            assertNotNull(stream);return new String(stream.readAllBytes(),StandardCharsets.UTF_8).lines().filter(line->!line.isBlank()).toList();
        }
    }
}
