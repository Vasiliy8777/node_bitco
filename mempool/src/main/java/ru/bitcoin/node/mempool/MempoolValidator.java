package ru.bitcoin.node.mempool;

import ru.bitcoin.node.consensus.transaction.ContextualTransactionValidator;
import ru.bitcoin.node.consensus.transaction.InputScriptValidator;
import ru.bitcoin.node.consensus.transaction.UtxoView;
import ru.bitcoin.node.mempool.policy.StandardScriptVerifyFlags;
import ru.bitcoin.node.protocol.transaction.Transaction;

public final class MempoolValidator {

    private MempoolValidator() {
    }

    /**
     * Проверяет транзакцию перед её принятием
     * в mempool.
     *
     * Здесь намеренно используются STANDARD
     * script verification flags, а не
     * ConsensusScriptFlags.forBlock(...).
     *
     * Это позволяет транзакции быть:
     *
     * consensus-valid,
     * но non-standard для mempool/relay.
     */
    public static void validate(
            Transaction transaction,
            long spendingHeight,
            UtxoView utxoView
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (utxoView == null) {
            throw new IllegalArgumentException(
                    "utxoView must not be null"
            );
        }

        /*
         * Contextual consensus checks:
         *
         * - referenced UTXOs exist
         * - coinbase maturity
         * - input/output amounts
         * - fee / money range
         *
         * Важно выполнять их до script validation,
         * поскольку script validator также
         * использует UTXO.
         */
        ContextualTransactionValidator.validate(
                transaction,
                spendingHeight,
                utxoView
        );

        /*
         * Mempool script validation.
         *
         * В отличие от block connection здесь
         * применяется STANDARD policy.
         */
        InputScriptValidator.validateAll(
                transaction,
                utxoView,
                StandardScriptVerifyFlags.STANDARD
        );
    }
}