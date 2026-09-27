package ru.bitcoin.node.p2p.transport;

import ru.bitcoin.node.p2p.message.BitcoinMessage;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** BIP324 application-message encoding: short type IDs or 12-byte v1 command fallback. */
public final class Bip324ApplicationCodec {
    private static final String[] SHORT = {
            null, "addr", "block", "blocktxn", "cmpctblock", "feefilter", "filteradd", "filterclear",
            "filterload", "getblocks", "getblocktxn", "getdata", "getheaders", "headers", "inv", "mempool",
            "merkleblock", "notfound", "ping", "pong", "sendcmpct", "tx", "getcfilters", "cfilter",
            "getcfheaders", "cfheaders", "getcfcheckpt", "cfcheckpt", "addrv2"
    };
    private static final Map<String,Integer> IDS;
    static {
        Map<String,Integer> ids = new HashMap<>();
        for (int i=1;i<SHORT.length;i++) ids.put(SHORT[i], i);
        IDS = Map.copyOf(ids);
    }
    private Bip324ApplicationCodec() { }

    public static byte[] encode(BitcoinMessage message) {
        Objects.requireNonNull(message, "message");
        byte[] payload = message.payload();
        Integer id = IDS.get(message.command());
        if (id != null) {
            byte[] out = new byte[1 + payload.length]; out[0] = id.byteValue();
            System.arraycopy(payload,0,out,1,payload.length); return out;
        }
        byte[] command = message.command().getBytes(StandardCharsets.US_ASCII);
        if (command.length == 0 || command.length > 12) throw new IllegalArgumentException("Bitcoin command must contain 1..12 ASCII bytes");
        for (byte b : command) if (b < 0x20 || b > 0x7e) throw new IllegalArgumentException("Bitcoin command must be printable ASCII");
        byte[] out = new byte[13 + payload.length];
        System.arraycopy(command,0,out,1,command.length); System.arraycopy(payload,0,out,13,payload.length); return out;
    }

    public static BitcoinMessage decode(byte[] contents) {
        Objects.requireNonNull(contents, "contents");
        if (contents.length < 1) throw new IllegalArgumentException("Empty BIP324 application message");
        int id = contents[0] & 0xff;
        String command; int payloadOffset;
        if (id == 0) {
            if (contents.length < 13) throw new IllegalArgumentException("Truncated BIP324 long message type");
            int end=1; while (end<13 && contents[end]!=0) end++;
            if (end==1) throw new IllegalArgumentException("Empty BIP324 long message type");
            for (int i=end;i<13;i++) if (contents[i]!=0) throw new IllegalArgumentException("Non-zero command padding");
            command = new String(contents,1,end-1,StandardCharsets.US_ASCII);
            for (int i=1;i<end;i++) if (contents[i]<0x20 || contents[i]>0x7e) throw new IllegalArgumentException("Non-printable BIP324 command");
            payloadOffset=13;
        } else {
            if (id >= SHORT.length || SHORT[id] == null) throw new IllegalArgumentException("Undefined BIP324 message type id: "+id);
            command=SHORT[id]; payloadOffset=1;
        }
        return new BitcoinMessage(command, Arrays.copyOfRange(contents,payloadOffset,contents.length));
    }
}
