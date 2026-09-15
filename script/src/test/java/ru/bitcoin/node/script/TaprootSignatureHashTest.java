package ru.bitcoin.node.script;

import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.protocol.serialization.TransactionParser;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.validation.TaprootValidator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TaprootSignatureHashTest {
    @TestFactory
    List<DynamicTest> officialBip341KeyPathVectors() throws IOException {
        try (var reader = new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/bip341/key-path-vectors.txt"), StandardCharsets.US_ASCII))) {
            return reader.lines().map(line -> {
                String[] fields = line.split("\\|");
                return DynamicTest.dynamicTest("BIP341 input " + fields[2] + " hash type " + fields[3], () -> {
                    var tx = TransactionParser.parse(HexUtils.decode(fields[0]));
                    var coins = Arrays.stream(fields[1].split(";")).map(coin -> {
                        String[] parts = coin.split(":");
                        return new TxOut(Long.parseLong(parts[0]), HexUtils.decode(parts[1]));
                    }).toList();
                    int input = Integer.parseInt(fields[2]);
                    assertArrayEquals(HexUtils.decode(fields[4]), TaprootSignatureHash.calculate(tx, input, coins,
                            Integer.parseInt(fields[3]), null, null, 0xffff_ffffL));
                    var inputs = new ArrayList<>(tx.inputs());
                    var original = inputs.get(input);
                    byte[] signature = HexUtils.decode(fields[5]);
                    inputs.set(input, new TxIn(original.previousOutput(), original.scriptSig(), original.sequence(), new Witness(List.of(signature))));
                    var signed = new Transaction(tx.version(), inputs, tx.outputs(), tx.lockTime());
                    byte[] key = Arrays.copyOfRange(coins.get(input).scriptPubKey(), 2, 34);
                    assertDoesNotThrow(() -> TaprootValidator.validate(signed, input, coins, key, ScriptVerifyFlags.TAPROOT));
                    signature[0] ^= 1;
                    inputs.set(input, new TxIn(original.previousOutput(), original.scriptSig(), original.sequence(), new Witness(List.of(signature))));
                    var invalid = new Transaction(tx.version(), inputs, tx.outputs(), tx.lockTime());
                    assertThrows(ScriptExecutionException.class, () -> TaprootValidator.validate(invalid, input, coins, key, ScriptVerifyFlags.TAPROOT));
                });
            }).toList();
        }
    }
}
