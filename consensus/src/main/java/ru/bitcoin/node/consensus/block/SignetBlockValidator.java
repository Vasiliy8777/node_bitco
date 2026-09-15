package ru.bitcoin.node.consensus.block;

import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.bytes.LittleEndian;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.transaction.InputScriptValidator;
import ru.bitcoin.node.consensus.transaction.UtxoEntry;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.protocol.block.Block;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.protocol.serialization.BitcoinReader;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.script.ScriptVerifyFlags;
import java.io.ByteArrayOutputStream;
import java.util.*;

/** BIP325 virtual transactions for the default public signet challenge. */
public final class SignetBlockValidator {
    private static final byte[] CHALLENGE = HexUtils.decode(
            "512103ad5e0edad18cb1f0fc0d28a3d4f1f3e445640337489abb10404f2d1e086be430"
            + "210359ef5021964fe22d6f8e05b2463c9540ce96883fe3b278760f048f5189f2e6c452ae");
    private static final byte[] HEADER = HexUtils.decode("ecc7daa2");
    private SignetBlockValidator() { }

    public static void validate(Block block, NetworkParameters parameters) {
        if (parameters.network() != BitcoinNetwork.SIGNET || block.hash().equals(parameters.genesisBlockHash())) return;
        validateChallenge(block, CHALLENGE);
    }

    public static void validateChallenge(Block block, byte[] challenge) {
        try {
            VirtualTransactions transactions = createTransactions(block, challenge);
            int flags = ScriptVerifyFlags.P2SH | ScriptVerifyFlags.WITNESS | ScriptVerifyFlags.DERSIG | ScriptVerifyFlags.NULLDUMMY;
            InputScriptValidator.validate(transactions.toSign(), 0,
                    point -> Optional.of(new UtxoEntry(0, challenge, 0, false)), flags);
        } catch (RuntimeException exception) {
            throw new BlockValidationException("Invalid signet block solution: " + exception.getMessage());
        }
    }

    public record VirtualTransactions(Transaction toSpend, Transaction toSign) { }

    public static VirtualTransactions createTransactions(Block block, byte[] challenge) {
        var coinbase = block.transactions().getFirst();
        int index = -1;
        for (int i = 0; i < coinbase.outputs().size(); i++) {
            byte[] script = coinbase.outputs().get(i).scriptPubKey();
            if (script.length >= 38 && Arrays.equals(Arrays.copyOf(script, 6), HexUtils.decode("6a24aa21a9ed"))) index = i;
        }
        if (index < 0) throw new BlockValidationException("Signet requires witness commitment");
        Extracted extracted = extract(coinbase.outputs().get(index).scriptPubKey());
        byte[] scriptSig = new byte[0];
        var stack = new ArrayList<byte[]>();
        if (extracted.solution() != null) {
            var reader = new BitcoinReader(extracted.solution());
            scriptSig = reader.readCompactBytes();
            int count = reader.checkedCollectionSize(reader.readCompactSize(), 1, "signet witness count");
            for (int i = 0; i < count; i++) stack.add(reader.readCompactBytes());
            if (reader.hasRemaining()) throw new BlockValidationException("Trailing signet solution data");
        }
        var outputs = new ArrayList<>(coinbase.outputs());
        outputs.set(index, new TxOut(outputs.get(index).value(), extracted.replacement()));
        var modified = new Transaction(coinbase.version(), coinbase.inputs(), outputs, coinbase.lockTime());
        var leaves = new ArrayList<ru.bitcoin.node.common.types.Hash256>();
        leaves.add(modified.txId());
        for (int i = 1; i < block.transactions().size(); i++) leaves.add(block.transactions().get(i).txId());
        var data = new ByteArrayOutputStream();
        data.writeBytes(LittleEndian.int32(block.header().version()));
        data.writeBytes(block.header().previousBlockHash().bytes());
        data.writeBytes(MerkleTree.calculateRoot(leaves).bytes());
        data.writeBytes(LittleEndian.uint32(block.header().timestamp().value()));
        var inputScript = new ByteArrayOutputStream(); inputScript.write(0); push(inputScript, data.toByteArray());
        var toSpend = new Transaction(0, List.of(new TxIn(OutPoint.coinbase(), inputScript.toByteArray(), new UInt32(0))),
                List.of(new TxOut(0, challenge)), new UInt32(0));
        var toSign = new Transaction(0, List.of(new TxIn(new OutPoint(toSpend.txId(), new UInt32(0)), scriptSig,
                new UInt32(0), new Witness(stack))), List.of(new TxOut(0, new byte[]{0x6a})), new UInt32(0));
        return new VirtualTransactions(toSpend, toSign);
    }

    private record Extracted(byte[] replacement, byte[] solution) { }
    private static Extracted extract(byte[] script) {
        var replacement = new ByteArrayOutputStream();
        byte[] solution = null;
        int position = 0;
        while (position < script.length) {
            int opcode = script[position++] & 0xff;
            long size = opcode <= 75 ? opcode : 0;
            if (opcode >= 76 && opcode <= 78) {
                int lengthBytes = 1 << (opcode - 76);
                if (script.length - position < lengthBytes) break;
                for (int i = 0; i < lengthBytes; i++) size |= (script[position++] & 0xffL) << (8 * i);
            }
            if (size > script.length - position) break;
            if (size > 0) {
                byte[] bytes = Arrays.copyOfRange(script, position, position + (int) size);
                position += (int) size;
                if (solution == null && bytes.length > 4 && Arrays.equals(Arrays.copyOf(bytes, 4), HEADER)) {
                    solution = Arrays.copyOfRange(bytes, 4, bytes.length);
                    bytes = HEADER;
                }
                push(replacement, bytes);
            } else {
                replacement.write(opcode);
            }
        }
        return new Extracted(solution == null ? script : replacement.toByteArray(), solution);
    }

    // CScript << vector uses a length push, including for single-byte numeric values.
    private static void push(ByteArrayOutputStream out, byte[] bytes) {
        if (bytes.length < 76) out.write(bytes.length);
        else if (bytes.length <= 255) { out.write(76); out.write(bytes.length); }
        else if (bytes.length <= 65535) { out.write(77); out.writeBytes(LittleEndian.uint16(bytes.length)); }
        else { out.write(78); out.writeBytes(LittleEndian.uint32(bytes.length)); }
        out.writeBytes(bytes);
    }
}
