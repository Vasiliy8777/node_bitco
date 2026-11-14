package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class OfflineSignatureEstimationTest {
    private static final byte[] KEY = HexFormat.of().parseHex("0279be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798");
    private static final Transaction TX = new Transaction(2,List.of(new TxIn(
            new OutPoint(new Hash256(new byte[32]),new UInt32(1)),new byte[0],new UInt32(0))),
            List.of(new TxOut(1,new byte[]{0x51})),new UInt32(0));
    private static final int FLAGS = ScriptVerifyFlags.P2SH|ScriptVerifyFlags.WITNESS|ScriptVerifyFlags.TAPROOT
            |ScriptVerifyFlags.DERSIG|ScriptVerifyFlags.STRICTENC|ScriptVerifyFlags.CHECKLOCKTIMEVERIFY;

    @Test void dummySchnorrNeverMakesTheNormalValidatorAcceptAnInvalidSignature() {
        byte[] program = new byte[34]; program[0] = 0x51; program[1] = 32; System.arraycopy(KEY,1,program,2,32);
        var coins = List.of(new TxOut(100,program)); var witness = new Witness(List.of(new byte[64]));
        assertThrows(ScriptExecutionException.class,() -> InputScriptValidator.validateKnownInputData(TX,0,coins,new byte[0],witness,FLAGS));
        assertDoesNotThrow(() -> InputScriptValidator.estimateKnownInputData(TX,0,coins,new byte[0],witness,FLAGS));
        var missing = assertThrows(ScriptExecutionException.class,() -> InputScriptValidator.validateKnownInputWithoutPrecomputedData(TX,0,coins,new byte[0],witness,FLAGS));
        assertEquals(ScriptError.SCHNORR_SIG_HASHTYPE,missing.code());
        assertThrows(ScriptExecutionException.class,() -> InputScriptValidator.validateKnownInputData(TX,0,coins,new byte[0],witness,FLAGS));
    }

    @Test void estimationStillRejectsMalformedEcdsaEncodings() {
        byte[] script = new byte[35]; script[0] = 33; System.arraycopy(KEY,0,script,1,33); script[34] = (byte)0xac;
        var coins = List.of(new TxOut(100,script));
        byte[] signature = new byte[71]; signature[0]=0x30;signature[1]=68;signature[2]=2;signature[3]=32;
        signature[4]=1;signature[36]=2;signature[37]=32;signature[38]=1;signature[70]=1;
        byte[] scriptSig = new byte[72]; scriptSig[0]=71;System.arraycopy(signature,0,scriptSig,1,71);
        assertDoesNotThrow(() -> InputScriptValidator.estimateKnownInputData(TX,0,coins,scriptSig,Witness.EMPTY,FLAGS));
        assertThrows(TransactionValidationException.class,() -> InputScriptValidator.validateKnownInputData(TX,0,coins,scriptSig,Witness.EMPTY,FLAGS));
        scriptSig[1]=0;
        assertThrows(TransactionValidationException.class,() -> InputScriptValidator.estimateKnownInputData(TX,0,coins,scriptSig,Witness.EMPTY,FLAGS));
    }

    @Test void dummyTimelockCheckerDoesNotBypassScriptOperandChecksOrLeakIntoAdmission() {
        var coins = List.of(new TxOut(100,new byte[]{0x51,(byte)0xb1,0x75,0x51}));
        assertThrows(TransactionValidationException.class,() -> InputScriptValidator.validateKnownInputData(TX,0,coins,new byte[0],Witness.EMPTY,FLAGS));
        assertDoesNotThrow(() -> InputScriptValidator.estimateKnownInputData(TX,0,coins,new byte[0],Witness.EMPTY,FLAGS));
        var negative = List.of(new TxOut(100,new byte[]{0x4f,(byte)0xb1,0x75,0x51}));
        assertThrows(TransactionValidationException.class,() -> InputScriptValidator.estimateKnownInputData(TX,0,negative,new byte[0],Witness.EMPTY,FLAGS));
        assertThrows(TransactionValidationException.class,() -> InputScriptValidator.validateKnownInputData(TX,0,coins,new byte[0],Witness.EMPTY,FLAGS));
    }
}
