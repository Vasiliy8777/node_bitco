package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.utxo.*;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class NodeValidationServiceTest {
    @TempDir Path directory;
    private static final NetworkParameters PARAMS = NetworkParametersRegistry.regtest();
    private static final byte[] SCRIPT = HexFormat.of().parseHex("a914" + HexFormat.of().formatHex(Hash160.hash(new byte[]{0x51})) + "87");

    @Test void confirmationAndReorgAutomaticallyUpdateMempoolIncludingChildren() {
        try (var db = new RocksDbDatabase(directory)) {
            var service = new NodeValidationService(db, PARAMS, () -> 1_800_000_000L, new Mempool());
            var genesis = service.activeTip();
            OutPoint fund = new OutPoint(Hash256.fromDisplayHex("11".repeat(32)), new UInt32(0));
            new RocksDbUtxoStore(db).save(fund, new StoredUtxo(100_000, SCRIPT, 0, false));
            var parent = spend(fund, 90_000);
            var child = spend(new OutPoint(parent.txId(), new UInt32(0)), 80_000);
            service.admit(parent);
            service.admit(child);
            assertEquals(2, service.mempoolEntries().size());
            var main = block(genesis, 1, List.of(parent));
            assertEquals(BlockProcessingResult.CONNECTED, service.processBlock(main));
            assertEquals(List.of(child.txId()), service.mempoolEntries().stream().map(e -> e.transaction().txId()).toList());
            var side = block(genesis, 2, List.of());
            assertEquals(BlockProcessingResult.STORED_SIDE_CHAIN_CONTEXT_PENDING, service.processBlock(side));
            var sideIndex = BlockIndexFactory.createChild(genesis, side.header());
            assertEquals(BlockProcessingResult.CONNECTED, service.processBlock(block(sideIndex, 3, List.of())));
            assertEquals(Set.of(parent.txId(), child.txId()), new HashSet<>(service.mempoolEntries().stream().map(e -> e.transaction().txId()).toList()));
        }
    }
    private static Transaction spend(OutPoint point, long value) {
        return new Transaction(2,List.of(new TxIn(point,new byte[]{1,0x51},TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(value,SCRIPT)),new UInt32(0));
    }
    private static Block block(BlockIndex parent, int tag, List<Transaction> spends) {
        var coinbase = new Transaction(1,List.of(new TxIn(OutPoint.coinbase(),
                new byte[]{(byte)(0x51 + parent.height()),(byte)tag},TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(5_000_000_000L,new byte[]{0x51})),new UInt32(0));
        List<Transaction> txs = new ArrayList<>(); txs.add(coinbase); txs.addAll(spends);
        var root = MerkleTree.calculateRoot(txs.stream().map(Transaction::txId).toList());
        for(long nonce=0;nonce<100_000;nonce++) {
            var header = new BlockHeader(4,parent.hash(),root,new UInt32(parent.header().timestamp().value()+1),new UInt32(0x207fffffL),new UInt32(nonce));
            if(ProofOfWork.isValid(header,PARAMS)) return new Block(header,txs);
        }
        throw new AssertionError("Could not mine regtest header");
    }
}
