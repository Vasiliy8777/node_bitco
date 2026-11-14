package ru.bitcoin.node.app.rpc;

import ru.bitcoin.node.common.bytes.LittleEndian;
import ru.bitcoin.node.common.encoding.CompactSize;
import ru.bitcoin.node.protocol.serialization.TransactionSerializer;
import ru.bitcoin.node.protocol.transaction.Transaction;

import java.io.ByteArrayOutputStream;
import java.util.Base64;

/** BIP174/BIP370 Creator serialization. No input signatures or signing data are included. */
final class PsbtCreator {
    private PsbtCreator() { }

    static String create(Transaction tx, Object requestedVersion) {
        long version = requestedVersion == null ? 2 : TransactionCreation.integer(requestedVersion, 0, 0xffff_ffffL);
        if (version != 0 && version != 2) throw new RpcException(-8, "The PSBT version can only be 2 or 0");
        var output = new ByteArrayOutputStream(); output.writeBytes(new byte[]{0x70, 0x73, 0x62, 0x74, (byte) 0xff});
        if (version == 0) entry(output, 0, TransactionSerializer.serializeLegacy(tx));
        else {
            entry(output, 2, LittleEndian.int32(tx.version()));
            entry(output, 3, LittleEndian.uint32(tx.lockTime().value()));
            entry(output, 4, CompactSize.encode(tx.inputs().size()));
            entry(output, 5, CompactSize.encode(tx.outputs().size()));
            entry(output, 0xfb, LittleEndian.int32(2));
        }
        output.write(0);
        for (var input : tx.inputs()) {
            if (version == 2) {
                entry(output, 0x0e, input.previousOutput().transactionId().bytes());
                entry(output, 0x0f, LittleEndian.uint32(input.previousOutput().outputIndex().value()));
                entry(output, 0x10, LittleEndian.uint32(input.sequence().value()));
            }
            output.write(0);
        }
        for (var txOut : tx.outputs()) {
            if (version == 2) {
                entry(output, 3, LittleEndian.int64(txOut.value()));
                entry(output, 4, txOut.scriptPubKey());
            }
            output.write(0);
        }
        return Base64.getEncoder().encodeToString(output.toByteArray());
    }

    private static void entry(ByteArrayOutputStream stream, int key, byte[] value) {
        stream.write(1); stream.write(key); stream.writeBytes(CompactSize.encode(value.length)); stream.writeBytes(value);
    }
}
