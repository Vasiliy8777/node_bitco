package ru.bitcoin.node.consensus.transaction;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.money.Money;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransactionValidatorTest {

    @Test
    void shouldAcceptValidTransaction() {

        Transaction transaction =
                transaction(
                        List.of(
                                input(
                                        "11",
                                        0
                                )
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[]{0x51}
                                )
                        )
                );

        assertDoesNotThrow(
                () -> TransactionValidator
                        .validateBasic(
                                transaction
                        )
        );
    }

    @Test
    void shouldRejectNegativeOutput() {

        Transaction transaction =
                transaction(
                        List.of(
                                input(
                                        "11",
                                        0
                                )
                        ),
                        List.of(
                                new TxOut(
                                        -1L,
                                        new byte[]{0x51}
                                )
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () -> TransactionValidator
                        .validateBasic(
                                transaction
                        )
        );
    }

    @Test
    void shouldRejectOutputAboveMaxMoney() {

        Transaction transaction =
                transaction(
                        List.of(
                                input(
                                        "11",
                                        0
                                )
                        ),
                        List.of(
                                new TxOut(
                                        Money.MAX_MONEY + 1,
                                        new byte[]{0x51}
                                )
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () -> TransactionValidator
                        .validateBasic(
                                transaction
                        )
        );
    }

    @Test
    void shouldRejectTotalAboveMaxMoney() {

        Transaction transaction =
                transaction(
                        List.of(
                                input(
                                        "11",
                                        0
                                )
                        ),
                        List.of(
                                new TxOut(
                                        Money.MAX_MONEY,
                                        new byte[]{0x51}
                                ),
                                new TxOut(
                                        1L,
                                        new byte[]{0x51}
                                )
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () -> TransactionValidator
                        .validateBasic(
                                transaction
                        )
        );
    }

    @Test
    void shouldRejectDuplicateInputs() {

        TxIn input =
                input(
                        "11",
                        0
                );

        Transaction transaction =
                transaction(
                        List.of(
                                input,
                                input
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[]{0x51}
                                )
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () -> TransactionValidator
                        .validateBasic(
                                transaction
                        )
        );
    }

    @Test
    void shouldRejectNullPrevoutInNonCoinbaseTransaction() {

        Transaction transaction =
                transaction(
                        List.of(
                                new TxIn(
                                        OutPoint.coinbase(),
                                        new byte[]{0x01},
                                        TxIn.FINAL_SEQUENCE
                                ),
                                input(
                                        "11",
                                        0
                                )
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[]{0x51}
                                )
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () -> TransactionValidator
                        .validateBasic(
                                transaction
                        )
        );
    }

    @Test
    void shouldRejectTooShortCoinbaseScriptSig() {

        Transaction transaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        OutPoint.coinbase(),
                                        new byte[]{0x01},
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        5_000L,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        assertThrows(
                TransactionValidationException.class,
                () -> TransactionValidator
                        .validateBasic(
                                transaction
                        )
        );
    }

    @Test
    void shouldAcceptCoinbaseScriptSigAtMinimumSize() {

        Transaction transaction =
                new Transaction(
                        1,
                        List.of(
                                new TxIn(
                                        OutPoint.coinbase(),
                                        new byte[]{
                                                0x01,
                                                0x01
                                        },
                                        TxIn.FINAL_SEQUENCE
                                )
                        ),
                        List.of(
                                new TxOut(
                                        5_000L,
                                        new byte[]{0x51}
                                )
                        ),
                        new UInt32(0)
                );

        assertDoesNotThrow(
                () -> TransactionValidator
                        .validateBasic(
                                transaction
                        )
        );
    }

    private static Transaction transaction(
            List<TxIn> inputs,
            List<TxOut> outputs
    ) {
        return new Transaction(
                1,
                inputs,
                outputs,
                new UInt32(0)
        );
    }

    private static TxIn input(
            String byteValue,
            long outputIndex
    ) {
        return new TxIn(
                new OutPoint(
                        Hash256.fromDisplayHex(
                                byteValue.repeat(32)
                        ),
                        new UInt32(
                                outputIndex
                        )
                ),
                new byte[0],
                TxIn.FINAL_SEQUENCE
        );
    }
    @Test
    void shouldAcceptTransactionBelowMaximumSize() {

        Transaction transaction =
                transaction(
                        List.of(
                                input(
                                        "11",
                                        0
                                )
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[100]
                                )
                        )
                );

        assertDoesNotThrow(
                () -> TransactionValidator
                        .validateBasic(
                                transaction
                        )
        );
    }
    @Test
    void shouldRejectTransactionAboveMaximumSize() {

        Transaction transaction =
                transaction(
                        List.of(
                                input(
                                        "11",
                                        0
                                )
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[1_000_000]
                                )
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () -> TransactionValidator
                        .validateBasic(
                                transaction
                        )
        );
    }
    @Test
    void shouldUseSerializedTransactionSizeForLimit() {

        Transaction transaction =
                transaction(
                        List.of(
                                input(
                                        "11",
                                        0
                                )
                        ),
                        List.of(
                                new TxOut(
                                        1_000L,
                                        new byte[999_950]
                                )
                        )
                );

        assertThrows(
                TransactionValidationException.class,
                () -> TransactionValidator
                        .validateBasic(
                                transaction
                        )
        );
    }
}