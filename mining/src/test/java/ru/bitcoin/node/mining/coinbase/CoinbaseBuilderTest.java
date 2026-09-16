package ru.bitcoin.node.mining.coinbase;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.block.*;
import ru.bitcoin.node.consensus.transaction.TransactionValidator;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CoinbaseBuilderTest {
    @Test void heightBoundariesAndHalvingPassExistingValidators() {
        var parameters=NetworkParametersRegistry.regtest();
        for(long height:new long[]{1,16,17,127,128,149,150,32768}) {
            var coinbase=CoinbaseBuilder.build(height,parameters,1000,new byte[]{0x51},new byte[0],List.of());
            assertDoesNotThrow(()->TransactionValidator.validateBasic(coinbase));
            assertDoesNotThrow(()->Bip34Validator.validate(coinbase,height,parameters));
            assertTrue(coinbase.inputs().getFirst().scriptSig().length>=2);
        }
        assertEquals(2_500_001_000L,CoinbaseBuilder.build(150,parameters,1000,new byte[]{0x51},new byte[0],List.of()).outputs().getFirst().value());
    }
    @Test void commitmentBindsSelectedTransactionWitnessAndOrder() {
        var tx=new Transaction(2,List.of(new TxIn(new OutPoint(new Hash256(new byte[32]),new UInt32(0)),
                new byte[0],TxIn.FINAL_SEQUENCE,new Witness(List.of(new byte[]{0x51})))),
                List.of(new TxOut(1,new byte[]{0x51})),new UInt32(0));
        var cb=CoinbaseBuilder.build(200,NetworkParametersRegistry.regtest(),0,new byte[]{0x51},new byte[]{1},List.of(tx));
        var header=new BlockHeader(4,new Hash256(new byte[32]),new Hash256(new byte[32]),new UInt32(1),new UInt32(0x207fffffL),new UInt32(0));
        assertDoesNotThrow(()->WitnessCommitmentValidator.validate(new Block(header,List.of(cb,tx)),true));
        assertThrows(BlockValidationException.class,()->WitnessCommitmentValidator.validate(new Block(header,List.of(cb)),true));
    }
    @Test void preSegwitCoinbaseHasNoWitnessAndBoundsAreEnforced() {
        var parameters=NetworkParametersRegistry.mainnet();
        var cb=CoinbaseBuilder.build(1,parameters,0,new byte[]{0x51},new byte[0],List.of());
        assertFalse(cb.hasWitness()); assertEquals(1,cb.outputs().size());
        assertThrows(IllegalArgumentException.class,()->CoinbaseBuilder.build(1,parameters,-1,new byte[]{0x51},new byte[0],List.of()));
        assertThrows(IllegalArgumentException.class,()->CoinbaseBuilder.build(1,parameters,0,new byte[]{0x51},new byte[100],List.of()));
        assertThrows(IllegalArgumentException.class,()->CoinbaseBuilder.build(1,parameters,0,new byte[]{0x51},new byte[0],List.of(cb)));
    }
}
