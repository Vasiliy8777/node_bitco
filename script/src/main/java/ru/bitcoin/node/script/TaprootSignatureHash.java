package ru.bitcoin.node.script;

import ru.bitcoin.node.common.bytes.LittleEndian;
import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.crypto.hash.Sha256;
import ru.bitcoin.node.crypto.hash.TaggedHash;
import ru.bitcoin.node.protocol.transaction.*;
import java.io.ByteArrayOutputStream;
import java.util.List;

public final class TaprootSignatureHash {
    private TaprootSignatureHash() { }

    public static byte[] calculate(Transaction tx, int input, List<TxOut> coins, int hashType,
                                   byte[] annex, byte[] leafHash, long codeSeparatorPosition) {
        if (input < 0 || input >= tx.inputs().size() || coins.size() != tx.inputs().size()) {
            throw new IllegalArgumentException("Invalid Taproot input context");
        }
        if (hashType != 0 && hashType != 1 && hashType != 2 && hashType != 3
                && hashType != 0x81 && hashType != 0x82 && hashType != 0x83) {
            throw new ScriptExecutionException("Invalid Taproot sighash type");
        }
        int base = hashType == 0 ? 1 : hashType & 3;
        boolean anyone = (hashType & 0x80) != 0;
        if (base == 3 && input >= tx.outputs().size()) throw new ScriptExecutionException("Taproot SINGLE without output");
        var msg = new ByteArrayOutputStream();
        msg.write(0); // epoch
        msg.write(hashType);
        msg.writeBytes(LittleEndian.int32(tx.version()));
        msg.writeBytes(LittleEndian.uint32(tx.lockTime().value()));
        if (!anyone) {
            var prevouts = new ByteArrayOutputStream();
            var amounts = new ByteArrayOutputStream();
            var scripts = new ByteArrayOutputStream();
            var sequences = new ByteArrayOutputStream();
            for (int i = 0; i < tx.inputs().size(); i++) {
                writeOutpoint(prevouts, tx.inputs().get(i).previousOutput());
                amounts.writeBytes(LittleEndian.int64(coins.get(i).value()));
                writeBytes(scripts, coins.get(i).scriptPubKey());
                sequences.writeBytes(LittleEndian.uint32(tx.inputs().get(i).sequence().value()));
            }
            for (var stream : List.of(prevouts, amounts, scripts, sequences)) msg.writeBytes(Sha256.hash(stream.toByteArray()));
        }
        if (base == 1) {
            var outputs = new ByteArrayOutputStream();
            for (var output : tx.outputs()) writeOutput(outputs, output);
            msg.writeBytes(Sha256.hash(outputs.toByteArray()));
        }
        msg.write((leafHash == null ? 0 : 2) + (annex == null ? 0 : 1));
        if (anyone) {
            writeOutpoint(msg, tx.inputs().get(input).previousOutput());
            writeOutput(msg, coins.get(input));
            msg.writeBytes(LittleEndian.uint32(tx.inputs().get(input).sequence().value()));
        } else {
            msg.writeBytes(LittleEndian.uint32(input));
        }
        if (annex != null) {
            var bytes = new ByteArrayOutputStream();
            writeBytes(bytes, annex);
            msg.writeBytes(Sha256.hash(bytes.toByteArray()));
        }
        if (base == 3) {
            var output = new ByteArrayOutputStream();
            writeOutput(output, tx.outputs().get(input));
            msg.writeBytes(Sha256.hash(output.toByteArray()));
        }
        if (leafHash != null) {
            msg.writeBytes(leafHash);
            msg.write(0); // key version
            msg.writeBytes(LittleEndian.uint32(codeSeparatorPosition));
        }
        return TaggedHash.hash("TapSighash", msg.toByteArray());
    }

    private static void writeOutpoint(ByteArrayOutputStream out, OutPoint point) {
        out.writeBytes(point.transactionId().bytes());
        out.writeBytes(LittleEndian.uint32(point.outputIndex().value()));
    }
    private static void writeOutput(ByteArrayOutputStream out, TxOut output) {
        out.writeBytes(LittleEndian.int64(output.value()));
        writeBytes(out, output.scriptPubKey());
    }
    private static void writeBytes(ByteArrayOutputStream out, byte[] bytes) {
        out.writeBytes(CompactSize.encode(bytes.length));
        out.writeBytes(bytes);
    }
}
