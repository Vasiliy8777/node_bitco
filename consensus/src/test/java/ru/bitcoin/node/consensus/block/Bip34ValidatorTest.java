package ru.bitcoin.node.consensus.block;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Bip34ValidatorTest {

    @Test
    void shouldEncodeSmallHeightUsingOp1() {
        assertArrayEquals(
                new byte[]{
                        0x51
                },
                Bip34Validator.encodeHeightPrefix(
                        1
                )
        );
    }

    @Test
    void shouldEncodeHeight17() {
        assertArrayEquals(
                new byte[]{
                        0x01,
                        0x11
                },
                Bip34Validator.encodeHeightPrefix(
                        17
                )
        );
    }

    @Test
    void shouldEncodeHeight128WithPositiveSignByte() {
        assertArrayEquals(
                new byte[]{
                        0x02,
                        (byte) 0x80,
                        0x00
                },
                Bip34Validator.encodeHeightPrefix(
                        128
                )
        );
    }

    @Test
    void shouldAcceptCorrectRegtestHeight() {

        Transaction coinbase =
                coinbase(
                        new byte[]{
                                0x01,
                                0x65
                        }
                );

        assertDoesNotThrow(
                () ->
                        Bip34Validator.validate(
                                coinbase,
                                101,
                                NetworkParametersRegistry.regtest()
                        )
        );
    }

    @Test
    void shouldAllowExtraCoinbaseDataAfterHeight() {

        Transaction coinbase =
                coinbase(
                        new byte[]{
                                0x01,
                                0x65,

                                /*
                                 * extraNonce / pool data etc.
                                 */
                                0x12,
                                0x34
                        }
                );

        assertDoesNotThrow(
                () ->
                        Bip34Validator.validate(
                                coinbase,
                                101,
                                NetworkParametersRegistry.regtest()
                        )
        );
    }

    @Test
    void shouldRejectIncorrectHeight() {

        Transaction coinbase =
                coinbase(
                        new byte[]{
                                0x01,
                                0x64
                        }
                );

        assertThrows(
                BlockValidationException.class,
                () ->
                        Bip34Validator.validate(
                                coinbase,
                                101,
                                NetworkParametersRegistry.regtest()
                        )
        );
    }

    @Test
    void shouldNotEnforceBeforeMainnetActivation() {

        Transaction coinbase =
                coinbase(
                        new byte[]{
                                0x01,
                                0x01
                        }
                );

        assertDoesNotThrow(
                () ->
                        Bip34Validator.validate(
                                coinbase,
                                227_930L,
                                NetworkParametersRegistry.mainnet()
                        )
        );
    }

    private static Transaction coinbase(
            byte[] scriptSig
    ) {
        return new Transaction(
                1,
                List.of(
                        new TxIn(
                                OutPoint.coinbase(),
                                scriptSig,
                                TxIn.FINAL_SEQUENCE
                        )
                ),
                List.of(
                        new TxOut(
                                5_000L,
                                new byte[]{
                                        0x51
                                }
                        )
                ),
                new UInt32(0)
        );
    }
}