package ru.bitcoin.node.consensus.transaction;
import ru.bitcoin.node.script.*;

import org.junit.jupiter.api.*;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.protocol.transaction.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.stream.*;
import static org.junit.jupiter.api.Assertions.*;

class BitcoinCoreScriptVectorsTest {
    @TestFactory Stream<DynamicTest> allScriptVectors() throws Exception {
        var resource = Objects.requireNonNull(getClass().getResourceAsStream("/bitcoin-core-v30/script-all.txt"));
        List<String> rows;
        try (var reader = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            rows = reader.lines().toList();
        }
        assertEquals(1212, rows.size());
        return IntStream.range(0, rows.size()).mapToObj(i -> DynamicTest.dynamicTest("Core script row " + (i+1), () -> {
            String[] fields = Arrays.stream(rows.get(i).split("\\|", -1))
                    .map(s -> new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8)).toArray(String[]::new);
            byte[] sig = assemble(fields[0]);
            long amount = Long.parseLong(fields[4]);
            List<byte[]> witness = new ArrayList<>();
            byte[] generatedOutput = null;
            if (Integer.parseInt(fields[5]) > 0) for (String item : fields[6].split(";", -1)) {
                if (item.startsWith("#SCRIPT#")) witness.add(assemble(item.substring(8)));
                else if (item.equals("#CONTROLBLOCK#")) {
                    var generator = ru.bitcoin.node.crypto.secp256k1.Secp256k1.DOMAIN.getG().normalize();
                    byte[] internal = Arrays.copyOfRange(generator.getEncoded(true), 1, 33);
                    byte[] script = witness.getLast();
                    byte[] leaf = ru.bitcoin.node.crypto.hash.TaggedHash.hash("TapLeaf", new byte[]{(byte) 0xc0},
                            ru.bitcoin.node.common.encoding.CompactSize.encode(script.length), script);
                    var tweak = new java.math.BigInteger(1, ru.bitcoin.node.crypto.hash.TaggedHash.hash("TapTweak", internal, leaf));
                    var point = generator.add(generator.multiply(tweak)).normalize();
                    byte[] encoded = point.getEncoded(true);
                    byte[] control = new byte[33]; control[0] = (byte) (0xc0 | (encoded[0] & 1));
                    System.arraycopy(internal, 0, control, 1, 32); witness.add(control);
                    generatedOutput = new byte[34]; generatedOutput[0] = 0x51; generatedOutput[1] = 0x20;
                    System.arraycopy(encoded, 1, generatedOutput, 2, 32);
                } else witness.add(HexFormat.of().parseHex(item));
            }
            byte[] pub = fields[1].equals("0x51 0x20 #TAPROOTOUTPUT#")
                    ? Objects.requireNonNull(generatedOutput) : assemble(fields[1]);
            assertEquals(Integer.parseInt(fields[5]), witness.size());            var credit = new Transaction(1, List.of(new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(0xffffffffL)),
                    new byte[]{0,0}, TxIn.FINAL_SEQUENCE)), List.of(new TxOut(amount, pub)), new UInt32(0));
            var spend = new Transaction(1, List.of(new TxIn(new OutPoint(credit.txId(), new UInt32(0)), sig, TxIn.FINAL_SEQUENCE, new Witness(witness))),
                    List.of(new TxOut(amount, new byte[0])), new UInt32(0));
            boolean accepted;
            try {
                InputScriptValidator.validateAll(spend, point -> Optional.of(new UtxoEntry(amount, pub, 1, true)),
                        ScriptVerifyFlags.parseNames(fields[2]));
                accepted = true;
            } catch (TransactionValidationException | ScriptExecutionException | ScriptParseException expected) {
                accepted = false;
            }
            assertEquals(fields[3].equals("OK"), accepted, String.join(" | ", fields));
        }));
    }
    static byte[] assemble(String text) throws Exception {
        var output = new ByteArrayOutputStream();
        var matcher = java.util.regex.Pattern.compile("'[^']*'|\\S+").matcher(text);
        while (matcher.find()) {
            String token = matcher.group();
            if (token.startsWith("0x")) output.writeBytes(HexFormat.of().parseHex(token.substring(2)));
            else if (token.startsWith("'")) push(output, token.substring(1, token.length()-1).getBytes(StandardCharsets.UTF_8));
            else if (token.matches("-?\\d+")) {
                long value = Long.parseLong(token);
                if (value == 0) output.write(0);
                else if (value == -1) output.write(0x4f);
                else if (value >= 1 && value <= 16) output.write((int) value + 0x50);
                else push(output, ScriptNumber.encode(value));
            } else output.write(Opcode.class.getField(token.startsWith("OP_") ? token : "OP_" + token).getInt(null));
        }
        return output.toByteArray();
    }
    private static void push(ByteArrayOutputStream out, byte[] bytes) {
        if (bytes.length < 76) out.write(bytes.length);
        else if (bytes.length <= 255) { out.write(76); out.write(bytes.length); }
        else { out.write(77); out.write(bytes.length & 255); out.write(bytes.length >>> 8); }
        out.writeBytes(bytes);
    }
}
