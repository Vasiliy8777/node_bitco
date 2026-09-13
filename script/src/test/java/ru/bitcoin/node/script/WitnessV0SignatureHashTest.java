package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.transaction.OutPoint;
import ru.bitcoin.node.protocol.transaction.Transaction;
import ru.bitcoin.node.protocol.transaction.TxIn;
import ru.bitcoin.node.protocol.transaction.TxOut;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class WitnessV0SignatureHashTest {

    @Test
    void shouldMatchOfficialBip143P2wpkhVector() {

        /*
         * Official BIP143 native/P2SH-P2WPKH
         * signature-hash example.
         *
         * Wire outpoint:
         *
         * db6b1b20aa0fd7b23880be2ecbd4a981
         * 30974cf4748fb66092ac4d3ceb1a5477
         * 01000000
         */
        OutPoint previousOutput =
                new OutPoint(
                        new Hash256(
                                hex(
                                        "db6b1b20"
                                                + "aa0fd7b2"
                                                + "3880be2e"
                                                + "cbd4a981"
                                                + "30974cf4"
                                                + "748fb660"
                                                + "92ac4d3c"
                                                + "eb1a5477"
                                )
                        ),
                        new UInt32(1L)
                );

        TxIn input =
                new TxIn(
                        previousOutput,
                        new byte[0],
                        new UInt32(
                                0xffff_fffeL
                        )
                );

        TxOut output0 =
                new TxOut(
                        199_996_600L,
                        hex(
                                "76a914"
                                        + "a457b684d7f0d539"
                                        + "a46a45bbc043f35b"
                                        + "59d0d963"
                                        + "88ac"
                        )
                );

        TxOut output1 =
                new TxOut(
                        800_000_000L,
                        hex(
                                "76a914"
                                        + "fd270b1ee6abcaea"
                                        + "97fea7ad0402e8bd"
                                        + "8ad6d77c"
                                        + "88ac"
                        )
                );

        Transaction transaction =
                new Transaction(
                        1,
                        List.of(input),
                        List.of(
                                output0,
                                output1
                        ),
                        new UInt32(
                                0x0000_0492L
                        )
                );

        /*
         * P2WPKH scriptCode:
         *
         * 76 a9 14
         * <20-byte pubKeyHash>
         * 88 ac
         *
         * CompactSize 0x19 is NOT included here;
         * calculate() adds script serialization itself.
         */
        byte[] scriptCode =
                hex(
                        "76a914"
                                + "79091972186c449e"
                                + "b1ded22b78e40d00"
                                + "9bdf0089"
                                + "88ac"
                );

        byte[] actual =
                WitnessV0SignatureHash.calculate(
                        transaction,
                        0,
                        scriptCode,
                        1_000_000_000L,
                        SignatureHashType.SIGHASH_ALL
                );

        byte[] expected =
                hex(
                        "64f3b0f4dd2bb3aa"
                                + "1ce8566d220cc74d"
                                + "da9df97d8490cc81"
                                + "d89d735c92e59fb6"
                );

        assertArrayEquals(
                expected,
                actual
        );
    }

    private static byte[] hex(
            String value
    ) {
        if ((value.length() & 1) != 0) {
            throw new IllegalArgumentException(
                    "Hex string length must be even"
            );
        }

        byte[] result =
                new byte[
                        value.length() / 2
                        ];

        for (int i = 0;
             i < result.length;
             i++) {

            int high =
                    Character.digit(
                            value.charAt(
                                    i * 2
                            ),
                            16
                    );

            int low =
                    Character.digit(
                            value.charAt(
                                    i * 2 + 1
                            ),
                            16
                    );

            if (high < 0 || low < 0) {
                throw new IllegalArgumentException(
                        "Invalid hex string"
                );
            }

            result[i] =
                    (byte) (
                            (high << 4)
                                    | low
                    );
        }

        return result;
    }
}