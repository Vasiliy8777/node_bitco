package ru.bitcoin.node.app.rpc;

// PSBT analysis: Copyright (c) 2009-present The Bitcoin Core developers.
// MIT license: /rpc/CORE-LICENSE.txt.

import ru.bitcoin.node.consensus.transaction.*;
import ru.bitcoin.node.mempool.policy.StandardScriptVerifyFlags;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.UnspendableScript;
import java.math.BigDecimal;
import java.util.*;

/** Core's analysis roles, ordered missing-data diagnostics and offline size estimate. */
final class PsbtAnalyzer {
    private static final long MAX_MONEY = 21_000_000L*100_000_000;
    private static final List<String> ROLES = List.of("creator","updater","signer","finalizer","extractor");

    static Map<String,Object> analyze(String encoded) {
        PsbtDocument document = PsbtDocument.parse(encoded);
        var unsigned = document.transaction(false);
        if (unsigned.isEmpty()) return invalid("PSBT cannot be made into a valid transaction");
        var verifier = new PsbtScriptVerifier(document);
        var signer = new PsbtFinalizer(document);
        var inputs = new ArrayList<Map<String,Object>>();
        boolean calculateFee = true; long inputAmount = 0; int next = 4;
        for (int index=0;index<document.inputs.size();index++) {
            TxOut coin = verifier.coin(index);
            var input = new LinkedHashMap<String,Object>();
            if (coin != null) {
                if (!moneyRange(coin.value()) || !moneyRange(inputAmount+coin.value()))
                    return invalid("PSBT is not valid. Input "+index+" has invalid value");
                inputAmount += coin.value();
                if (UnspendableScript.isUnspendable(coin.scriptPubKey()))
                    return invalid("PSBT is not valid. Input "+index+" spends unspendable output");
            } else calculateFee = false;
            input.put("has_utxo",coin != null);
            boolean isFinal = verifier.signedAndVerified(document.inputs.get(index),index);
            input.put("is_final",isFinal);
            int role = 4;
            if (!isFinal) {
                var missing = new PsbtMissing();
                boolean complete = signer.signInput(index,false,false,missing);
                role = complete ? 3 : missing.onlySignatures() ? 2 : 1;
                Map<String,Object> details = missing.json();
                if (!complete && !details.isEmpty()) input.put("missing",details);
            }
            input.put("next",ROLES.get(role)); next = Math.min(next,role); inputs.add(input);
        }
        var result = new LinkedHashMap<String,Object>();
        if (!inputs.isEmpty()) result.put("inputs",inputs);
        if (calculateFee) {
            long outputAmount = 0;
            for (TxOut output : unsigned.get().outputs()) {
                if (!moneyRange(output.value()) || !moneyRange(outputAmount+output.value()))
                    return invalid("PSBT is not valid. Output amount invalid");
                outputAmount += output.value();
            }
            long fee = inputAmount-outputAmount;
            result.put("fee",BigDecimal.valueOf(fee,8));
            boolean estimated = true;
            for (int index=0;index<document.inputs.size();index++) {
                if (!signer.signInput(index,false,true,null)) { estimated = false; break; }
            }
            if (estimated) {
                Transaction transaction = extracted(document);
                var coins = new HashMap<OutPoint,UtxoEntry>();
                for (int index=0;index<transaction.inputs().size();index++) {
                    TxOut coin = verifier.coin(index);
                    coins.put(transaction.inputs().get(index).previousOutput(),new UtxoEntry(coin.value(),coin.scriptPubKey(),1,false));
                }
                long sigops = TransactionSigOpCost.calculate(transaction,point -> Optional.ofNullable(coins.get(point)),StandardScriptVerifyFlags.STANDARD);
                long size = (Math.max(TransactionWeight.calculate(transaction),sigops*20)+3)/4;
                result.put("estimated_vsize",size);
                result.put("estimated_feerate",BigDecimal.valueOf(Math.floorDiv(fee*1000,size),8));
            }
        }
        result.put("next",ROLES.get(next));
        return result;
    }

    private static Transaction extracted(PsbtDocument document) {
        Transaction unsigned = document.transaction(false).orElseThrow();
        var inputs = new ArrayList<TxIn>();
        for (int index=0;index<unsigned.inputs().size();index++) {
            TxIn original = unsigned.inputs().get(index); var fields = document.inputs.get(index);
            inputs.add(new TxIn(original.previousOutput(),fields.has(7)?fields.field(7).value():new byte[0],
                    original.sequence(),fields.has(8)?(Witness)fields.field(8).decoded():Witness.EMPTY));
        }
        return new Transaction(unsigned.version(),inputs,unsigned.outputs(),unsigned.lockTime());
    }
    private static boolean moneyRange(long value) { return value >= 0 && value <= MAX_MONEY; }
    private static Map<String,Object> invalid(String error) { return Map.of("next","creator","error",error); }
}
