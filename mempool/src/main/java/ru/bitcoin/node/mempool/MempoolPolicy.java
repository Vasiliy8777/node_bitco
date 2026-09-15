package ru.bitcoin.node.mempool;

import ru.bitcoin.node.consensus.transaction.TransactionWeight;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.util.Objects;

public final class MempoolPolicy {
    public static final int MAX_STANDARD_SCRIPTSIG_SIZE =
            1_650;

    public static final int TX_MIN_STANDARD_VERSION =
            1;

    public static final int TX_MAX_STANDARD_VERSION =
            3;
    public static final long MAX_STANDARD_TX_WEIGHT =
            400_000L;

    public static final int MIN_STANDARD_TX_NONWITNESS_SIZE =
            65;
    /*
     * Bitcoin Core current default:
     *
     * DEFAULT_MIN_RELAY_TX_FEE = 100 sat/kvB
     *
     * Это node policy, не consensus.
     */
    public static final FeeRate DEFAULT_MIN_RELAY_FEE_RATE =
            new FeeRate(100L);

    private final FeeRate minRelayFeeRate;
    private final int maxDataCarrierBytes;
    private final long dustRelaySatPerKvB;

    public MempoolPolicy() {
        this(DEFAULT_MIN_RELAY_FEE_RATE);
    }

    public MempoolPolicy(
            FeeRate minRelayFeeRate
    ) {
        this(minRelayFeeRate, 100_000, 3000);
    }
    public MempoolPolicy(FeeRate minRelayFeeRate, int maxDataCarrierBytes, long dustRelaySatPerKvB) {
        if (maxDataCarrierBytes < 0 || dustRelaySatPerKvB < 0) throw new IllegalArgumentException("Invalid relay policy");
        this.maxDataCarrierBytes = maxDataCarrierBytes;
        this.dustRelaySatPerKvB = dustRelaySatPerKvB;
        this.minRelayFeeRate =
                Objects.requireNonNull(
                        minRelayFeeRate,
                        "minRelayFeeRate"
                );
    }

    public FeeRate minRelayFeeRate() {
        return minRelayFeeRate;
    }
    public long dustRelaySatPerKvB() { return dustRelaySatPerKvB; }
    public int maxDataCarrierBytes() { return maxDataCarrierBytes; }
    public void validateStandardStructure(
            Transaction transaction,
            long weight
    ) {
        if (transaction == null) {
            throw new IllegalArgumentException(
                    "transaction must not be null"
            );
        }

        if (weight <= 0) {
            throw new IllegalArgumentException(
                    "weight must be positive"
            );
        }

        int version =
                transaction.version();

        if (version < TX_MIN_STANDARD_VERSION
                || version > TX_MAX_STANDARD_VERSION) {

            throw new MempoolAdmissionException(
                    "Non-standard transaction version: "
                            + version
                            + ", allowed range: "
                            + TX_MIN_STANDARD_VERSION
                            + ".."
                            + TX_MAX_STANDARD_VERSION
            );
        }

        for (int i = 0;
             i < transaction.inputs().size();
             i++) {

            int scriptSigSize =
                    transaction.inputs()
                            .get(i)
                            .scriptSig()
                            .length;

            if (scriptSigSize
                    > MAX_STANDARD_SCRIPTSIG_SIZE) {

                throw new MempoolAdmissionException(
                        "Input "
                                + i
                                + " scriptSig exceeds maximum standard size: "
                                + scriptSigSize
                                + " > "
                                + MAX_STANDARD_SCRIPTSIG_SIZE
                );
            }
        }

        /*
         * Bitcoin Core policy:
         *
         * MAX_STANDARD_TX_WEIGHT = 400,000 WU.
         *
         * Это policy, а не block-consensus limit.
         * Consensus block limit остаётся 4,000,000 WU.
         */
        if (weight > MAX_STANDARD_TX_WEIGHT) {
            throw new MempoolAdmissionException(
                    "Transaction exceeds maximum standard weight: "
                            + weight
                            + " > "
                            + MAX_STANDARD_TX_WEIGHT
            );
        }

        /*
         * Важно: проверяется именно размер
         * non-witness serialization.
         *
         * Witness bytes не могут использоваться,
         * чтобы "дотянуть" слишком маленькую
         * transaction до 65 bytes.
         */
        int nonWitnessSize =
                TransactionSerializer.serializeLegacy(
                        transaction
                ).length;

        if (nonWitnessSize
                < MIN_STANDARD_TX_NONWITNESS_SIZE) {

            throw new MempoolAdmissionException(
                    "Transaction non-witness size is below standard minimum: "
                            + nonWitnessSize
                            + " < "
                            + MIN_STANDARD_TX_NONWITNESS_SIZE
            );
        }
        ru.bitcoin.node.mempool.policy.StandardTransactionPolicy.validateStructure(transaction, maxDataCarrierBytes, dustRelaySatPerKvB);
    }
    /**
     * Проверяет fee транзакции относительно
     * минимальной relay feerate.
     */
    public void validateFee(
            long fee,
            long weight
    ) {
        if (fee < 0) {
            throw new IllegalArgumentException(
                    "fee must not be negative"
            );
        }

        if (weight <= 0) {
            throw new IllegalArgumentException(
                    "weight must be positive"
            );
        }

        long virtualSize =
                TransactionWeight.virtualSize(
                        weight
                );

        long requiredFee =
                minRelayFeeRate.feeForVSize(
                        virtualSize
                );

        if (fee < requiredFee) {
            throw new MempoolAdmissionException(
                    "Minimum relay fee not met: "
                            + fee
                            + " < "
                            + requiredFee
                            + " sat"
            );
        }
    }
}
