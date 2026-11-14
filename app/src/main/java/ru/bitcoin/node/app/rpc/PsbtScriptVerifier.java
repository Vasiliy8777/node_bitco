package ru.bitcoin.node.app.rpc;

import ru.bitcoin.node.consensus.transaction.InputScriptValidator;
import ru.bitcoin.node.consensus.transaction.TransactionValidationException;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxOut;
import ru.bitcoin.node.protocol.transaction.Witness;
import ru.bitcoin.node.script.ScriptExecutionException;
import ru.bitcoin.node.mempool.policy.StandardScriptVerifyFlags;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One immutable transaction/coin snapshot shared by the offline PSBT roles. */
final class PsbtScriptVerifier {
    private final Transaction transaction;
    private final List<TxOut> coins;

    PsbtScriptVerifier(PsbtDocument document) {
        transaction = document.transaction(false).orElse(null);
        var snapshot = new ArrayList<TxOut>(document.inputs.size());
        for (int index = 0; index < document.inputs.size(); index++) {
            var input = document.inputs.get(index);
            TxOut coin = null;
            if (transaction != null && input.has(0)) {
                Transaction previous = (Transaction) input.field(0).decoded();
                var outpoint = transaction.inputs().get(index).previousOutput();
                if (previous.txId().equals(outpoint.transactionId())
                        && outpoint.outputIndex().value() < previous.outputs().size())
                    coin = previous.outputs().get((int) outpoint.outputIndex().value());
            } else if (input.has(1)) {
                TxOut witnessCoin = (TxOut) input.field(1).decoded();
                // CTxOut::IsNull uses the -1 sentinel, not MoneyRange.
                if (witnessCoin.value() != -1) coin = witnessCoin;
            }
            snapshot.add(coin);
        }
        coins = Collections.unmodifiableList(snapshot);
    }

    TxOut coin(int index) { return coins.get(index); }

    boolean signedAndVerified(PsbtDocument.Fields input, int index) {
        return signedAndVerified(input,index,true);
    }
    boolean signedAndVerified(PsbtDocument.Fields input,int index,boolean precomputed) {
        byte[] scriptSig = input.has(7) ? input.field(7).value() : new byte[0];
        Witness witness = input.has(8) ? (Witness) input.field(8).decoded() : Witness.EMPTY;
        return verify(index,scriptSig,witness,false,precomputed);
    }

    boolean verify(int index, byte[] scriptSig, Witness witness) {
        return verify(index,scriptSig,witness,false);
    }
    boolean verify(int index,byte[] scriptSig,Witness witness,boolean estimate) {
        return verify(index,scriptSig,witness,estimate,true);
    }
    private boolean verify(int index,byte[] scriptSig,Witness witness,boolean estimate,boolean precomputed) {
        if (transaction == null || coins.get(index) == null) return false;
        try {
            if(estimate) InputScriptValidator.estimateKnownInputData(transaction,index,coins,scriptSig,witness,StandardScriptVerifyFlags.STANDARD);
            else if(!precomputed) InputScriptValidator.validateKnownInputWithoutPrecomputedData(transaction,index,coins,scriptSig,witness,StandardScriptVerifyFlags.STANDARD);
            else InputScriptValidator.validateKnownInputData(transaction, index, coins,
                    scriptSig, witness, StandardScriptVerifyFlags.STANDARD);
            return true;
        } catch (ScriptExecutionException | TransactionValidationException invalid) {
            return false;
        }
    }
}
