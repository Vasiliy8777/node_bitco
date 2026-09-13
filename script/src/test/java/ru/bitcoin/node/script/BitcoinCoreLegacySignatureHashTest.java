package ru.bitcoin.node.script;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.protocol.serialization.TransactionParser;
import ru.bitcoin.node.protocol.transaction.Transaction;

import static org.junit.jupiter.api.Assertions.assertEquals;


class BitcoinCoreLegacySignatureHashTest {

    @Test
    void shouldMatchBitcoinCoreVector1() {

        assertBitcoinCoreVector(
                "907c2bc503ade11cc3b04eb2918b6f547b0630ab569273824748c87ea14b0696526c66ba740200000004ab65ababfd1f9bdd4ef073c7afc4ae00da8a66f429c917a0081ad1e1dabce28d373eab81d8628de802000000096aab5253ab52000052ad042b5f25efb33beec9f3364e8a9139e8439d9d7e26529c3c30b6c3fd89f8684cfd68ea0200000009ab53526500636a52ab599ac2fe02a526ed040000000008535300516352515164370e010000000003006300ab2ec229",
                "",
                2,
                1864164639,
                "31af167a6cf3f9d5f6875caa4d31704ceb0eba078d132b78dab52c3b8997317e"
        );
    }

    @Test
    void shouldMatchBitcoinCoreVector2() {

        assertBitcoinCoreVector(
                "a0aa3126041621a6dea5b800141aa696daf28408959dfb2df96095db9fa425ad3f427f2f6103000000015360290e9c6063fa26912c2e7fb6a0ad80f1c5fea1771d42f12976092e7a85a4229fdb6e890000000001abc109f6e47688ac0e4682988785744602b8c87228fcef0695085edf19088af1a9db126e93000000000665516aac536affffffff8fe53e0806e12dfd05d67ac68f4768fdbe23fc48ace22a5aa8ba04c96d58e2750300000009ac51abac63ab5153650524aa680455ce7b000000000000499e50030000000008636a00ac526563ac5051ee030000000003abacabd2b6fe000000000003516563910fb6b5",
                "65",
                0,
                -1391424484,
                "48d6a1bd2cd9eec54eb866fc71209418a950402b5d7e52363bfb75c98e141175"
        );
    }

    @Test
    void shouldMatchBitcoinCoreVectorWithCodeSeparator() {

        assertBitcoinCoreVector(
                "6e7e9d4b04ce17afa1e8546b627bb8d89a6a7fefd9d892ec8a192d79c2ceafc01694a6a7e7030000000953ac6a51006353636a33bced1544f797f08ceed02f108da22cd24c9e7809a446c61eb3895914508ac91f07053a01000000055163ab516affffffff11dc54eee8f9e4ff0bcf6b1a1a35b1cd10d63389571375501af7444073bcec3c02000000046aab53514a821f0ce3956e235f71e4c69d91abe1e93fb703bd33039ac567249ed339bf0ba0883ef300000000090063ab65000065ac654bec3cc504bcf499020000000005ab6a52abac64eb060100000000076a6a5351650053bbbc130100000000056a6aab53abd6e1380100000000026a51c4e509b8",
                "acab655151",
                0,
                479279909,
                "2a3d95b09237b72034b23f2d2bb29fa32a58ab5c6aa72f6aafdfa178ab1dd01c"
        );
    }

    @Test
    void shouldMatchAnotherBitcoinCoreVector() {

        assertBitcoinCoreVector(
                "73107cbd025c22ebc8c3e0a47b2a760739216a528de8d4dab5d45cbeb3051cebae73b01ca10200000007ab6353656a636affffffffe26816dffc670841e6a6c8c61c586da401df1261a330a6c6b3dd9f9a0789bc9e000000000800ac6552ac6aac51ffffffff0174a8f0010000000004ac52515100000000",
                "5163ac63635151ac",
                1,
                1190874345,
                "06e328de263a87b09beabe222a21627a6ea5c7f560030da31610c4611f4a46bc"
        );
    }

    private static void assertBitcoinCoreVector(
            String rawTransactionHex,
            String scriptHex,
            int inputIndex,
            int hashType,
            String expectedDisplayHash
    ) {

        Transaction transaction =
                TransactionParser.parse(
                        HexUtils.decode(
                                rawTransactionHex
                        )
                );

        byte[] scriptCode =
                HexUtils.decode(
                        scriptHex
                );

        byte[] actual =
                LegacySignatureHash.calculate(
                        transaction,
                        inputIndex,
                        scriptCode,
                        hashType
                );

        /*
         * Bitcoin Core's sighash.json writes uint256
         * in display hex form.
         *
         * Our calculate() returns raw digest bytes.
         */
        String actualDisplayHash =
                new Hash256(
                        actual
                ).toDisplayHex();

        assertEquals(
                expectedDisplayHash,
                actualDisplayHash
        );
    }
}