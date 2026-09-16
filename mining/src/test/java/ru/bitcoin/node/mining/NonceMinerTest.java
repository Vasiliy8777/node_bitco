package ru.bitcoin.node.mining;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.block.GenesisBlockFactory;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import static org.junit.jupiter.api.Assertions.*;

class NonceMinerTest {
    @Test void findsKnownGenesisNonceAndPreservesBlockBody() {
        var parameters=NetworkParametersRegistry.regtest();
        var block=GenesisBlockFactory.create(parameters);
        var found=NonceMiner.search(block,parameters,block.header().nonce().value(),1,()->false).orElseThrow();
        assertEquals(block.header().hash(),found.header().hash());
        assertEquals(block.transactions(),found.transactions());
        assertTrue(ProofOfWork.isValid(found.header(),parameters));
    }
    @Test void cancellationAndExhaustionReturnNoBlock() {
        var p=NetworkParametersRegistry.regtest(); var b=GenesisBlockFactory.create(p);
        assertTrue(NonceMiner.search(b,p,0,100,()->true).isEmpty());
        assertTrue(NonceMiner.search(b,p,0,0,()->false).isEmpty());
        assertThrows(IllegalArgumentException.class,()->NonceMiner.search(b,p,0xffffffffL,2,()->false));
        assertDoesNotThrow(()->NonceMiner.search(b,p,0xffffffffL,1,()->false));
    }
}
