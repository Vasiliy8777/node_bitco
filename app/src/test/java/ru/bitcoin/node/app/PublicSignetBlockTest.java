package ru.bitcoin.node.app;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.chain.BlockProcessingResult;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.block.*;
import ru.bitcoin.node.mempool.Mempool;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.BlockParser;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import ru.bitcoin.node.storage.utxo.RocksDbUtxoStore;
import java.nio.file.Path;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

class PublicSignetBlockTest {
    @Test void modifiedCoinbaseFailsChallengeEvenWithRecomputedMerkleRoot() throws Exception {
        var original=fixture();
        var coinbase=original.transactions().getFirst();
        var outputs=new java.util.ArrayList<>(coinbase.outputs());
        var payout=outputs.getFirst();
        outputs.set(0,new ru.bitcoin.node.protocol.transaction.TxOut(payout.value()-1,payout.scriptPubKey()));
        var changed=new ru.bitcoin.node.protocol.transaction.Transaction(coinbase.version(),coinbase.inputs(),outputs,coinbase.lockTime());
        var transactions=new java.util.ArrayList<>(original.transactions()); transactions.set(0,changed);
        var h=original.header();
        var root=ru.bitcoin.node.crypto.merkle.MerkleTree.calculateRoot(transactions.stream()
                .map(ru.bitcoin.node.protocol.transaction.Transaction::txId).toList());
        var block=new Block(new BlockHeader(h.version(),h.previousBlockHash(),root,h.timestamp(),h.bits(),h.nonce()),transactions);
        assertDoesNotThrow(()->BlockValidator.validateStructure(block));
        assertDoesNotThrow(()->WitnessCommitmentValidator.validate(block,true));
        assertThrows(BlockValidationException.class,()->SignetBlockValidator.validate(block,NetworkParametersRegistry.signet()));
    }
    @Test void nonceIsNotPartOfSignetSignatureMessage() throws Exception {
        var original=fixture(); var h=original.header();
        var block=new Block(new BlockHeader(h.version(),h.previousBlockHash(),h.merkleRoot(),h.timestamp(),h.bits(),
                new UInt32((h.nonce().value()+1)&0xffffffffL)),original.transactions());
        // Challenge verification alone must permit nonce search after signing.
        assertDoesNotThrow(()->SignetBlockValidator.validate(block,NetworkParametersRegistry.signet()));
    }
    @TempDir Path directory;
    private Block fixture() throws Exception {
        try(var in=Objects.requireNonNull(getClass().getResourceAsStream("/public-signet/1.bin"))) {
            return BlockParser.parse(in.readAllBytes());
        }
    }
    @Test void publicChallengeAndFullBlockConnectionAcceptRealBlock() throws Exception {
        var block=fixture(); var parameters=NetworkParametersRegistry.signet();
        assertEquals("00000086d6b2636cb2a392d45edc4ec544a10024d30141c9adf4bfd9de533b53",block.hash().toDisplayHex());
        assertDoesNotThrow(()->SignetBlockValidator.validate(block,parameters));
        try(var db=new RocksDbDatabase(directory)) {
            var service=new NodeValidationService(db,parameters,()->1_800_000_000L,new Mempool());
            assertEquals(BlockProcessingResult.CONNECTED,service.processBlock(block));
            assertEquals(block.hash(),service.activeTip().hash());
            assertEquals(1,service.activeTip().height());
            assertTrue(new RocksDbUtxoStore(db).count()>0);
        }
        try(var db=new RocksDbDatabase(directory)) {
            var service=new NodeValidationService(db,parameters,()->1_800_000_000L,new Mempool());
            assertEquals(block.hash(),service.activeTip().hash());
        }
    }
    @Test void changedSignedTimeFailsChallengeWithoutRelyingOnPow() throws Exception {
        var block=fixture(); var h=block.header();
        var altered=new Block(new BlockHeader(h.version(),h.previousBlockHash(),h.merkleRoot(),
                new UInt32(h.timestamp().value()+1),h.bits(),h.nonce()),block.transactions());
        assertThrows(BlockValidationException.class,()->SignetBlockValidator.validate(altered,NetworkParametersRegistry.signet()));
    }
}
