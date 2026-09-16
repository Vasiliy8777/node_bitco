package ru.bitcoin.node.mining;
import org.junit.jupiter.api.Test;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.storage.utxo.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class BlockTemplateBuilderTest {
    @Test void computesFeesFromCoinsAndDoesNotMutateStore() {
        var params=NetworkParametersRegistry.regtest();
        var parent=BlockIndexFactory.createGenesis(GenesisBlockFactory.create(params).header());
        var point=new OutPoint(Hash256.fromDisplayHex("11".repeat(32)),new UInt32(0));
        UtxoStore store=new UtxoStore() {
            public Optional<StoredUtxo> find(OutPoint p) {return p.equals(point)?Optional.of(new StoredUtxo(10000,new byte[]{0x51},0,false)):Optional.empty();}
            public void save(OutPoint p,StoredUtxo coin) {fail("Unexpected persistent write");}
            public void delete(OutPoint p) {fail("Unexpected persistent deletion");}
        };
        var tx=new Transaction(2,List.of(new TxIn(point,new byte[0],TxIn.FINAL_SEQUENCE)),List.of(new TxOut(9000,new byte[]{0x51})),new UInt32(0));
        var time=new UInt32(parent.header().timestamp().value()+1);
        var block=BlockTemplateBuilder.build(parent,h->h.equals(parent.hash())?parent:null,store,params,4,time,parent.header().bits(),new byte[]{0x51},new byte[0],List.of(tx));
        assertEquals(5_000_001_000L,block.transactions().getFirst().outputs().getFirst().value());
        assertEquals(tx.txId(),block.transactions().get(1).txId());
        var mined=NonceMiner.search(block,params,0,100_000,()->false).orElseThrow();
        assertTrue(ru.bitcoin.node.consensus.pow.ProofOfWork.isValid(mined.header(),params));
        assertEquals(block.header().merkleRoot(),mined.header().merkleRoot());
        assertThrows(RuntimeException.class,()->BlockTemplateBuilder.build(parent,h->parent,store,params,4,time,parent.header().bits(),new byte[]{0x51},new byte[0],List.of(tx,tx)));
        assertTrue(store.find(point).isPresent());
        var snapshot=List.of(new ru.bitcoin.node.mempool.MempoolEntry(tx,1000,
                ru.bitcoin.node.consensus.transaction.TransactionWeight.calculate(tx),0,0));
        var selected=BlockTemplateBuilder.fromMempool(parent,h->parent,store,params,4,time,parent.header().bits(),
                new byte[]{0x51},new byte[0],snapshot,4_000_000,new ru.bitcoin.node.mempool.FeeRate(0));
        assertEquals(2,selected.transactions().size());
        assertEquals(5_000_001_000L,selected.transactions().getFirst().outputs().getFirst().value());
        long reserve=356+ru.bitcoin.node.consensus.transaction.TransactionWeight.calculate(selected.transactions().getFirst());
        var empty=BlockTemplateBuilder.fromMempool(parent,h->parent,store,params,4,time,parent.header().bits(),
                new byte[]{0x51},new byte[0],snapshot,reserve,new ru.bitcoin.node.mempool.FeeRate(0));
        assertEquals(1,empty.transactions().size());
        assertThrows(IllegalArgumentException.class,()->BlockTemplateBuilder.fromMempool(parent,h->parent,store,params,4,time,
                parent.header().bits(),new byte[]{0x51},new byte[0],snapshot,reserve-1,new ru.bitcoin.node.mempool.FeeRate(0)));
    }
}
