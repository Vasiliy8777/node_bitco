package ru.bitcoin.node.storage.utxo;

import ru.bitcoin.node.crypto.secp256k1.PublicKey;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/** Bitcoin Core Coin/TxOutCompression codec used by UTXO snapshot v2. */
public final class UtxoSnapshotCoinCodec {
    private static final long MAX_MONEY = 21_000_000L * 100_000_000L;
    private static final int MAX_SCRIPT_SIZE = 10_000;
    private UtxoSnapshotCoinCodec() {}

    public static void write(OutputStream out, StoredUtxo coin) throws IOException {
        if (coin.height() < 0 || coin.height() > 0x7fff_ffffL) throw new IOException("Invalid coin height");
        if (coin.amount() < 0 || coin.amount() > MAX_MONEY) throw new IOException("Invalid coin amount");
        UtxoSnapshotWriter.writeVarInt(out, Math.addExact(Math.multiplyExact(coin.height(), 2), coin.coinbase() ? 1 : 0));
        UtxoSnapshotWriter.writeVarInt(out, UtxoSnapshotWriter.compressAmount(coin.amount()));
        writeScript(out, coin.scriptPubKey());
    }

    public static StoredUtxo read(InputStream in) throws IOException {
        long code = readVarInt(in);
        long height = code >>> 1;
        boolean coinbase = (code & 1) != 0;
        long amount = decompressAmount(readVarInt(in));
        if (amount < 0 || amount > MAX_MONEY) throw new IOException("Snapshot coin amount outside MoneyRange");
        return new StoredUtxo(amount, readScript(in), height, coinbase);
    }

    static void writeScript(OutputStream out, byte[] script) throws IOException {
        if (isP2pkh(script)) {
            UtxoSnapshotWriter.writeVarInt(out, 0); out.write(script, 3, 20); return;
        }
        if (isP2sh(script)) {
            UtxoSnapshotWriter.writeVarInt(out, 1); out.write(script, 2, 20); return;
        }
        if (script.length == 35 && script[0] == 33 && script[34] == (byte)0xac && (script[1] == 2 || script[1] == 3)) {
            UtxoSnapshotWriter.writeVarInt(out, script[1] & 0xff); out.write(script, 2, 32); return;
        }
        if (script.length == 67 && script[0] == 65 && script[66] == (byte)0xac && script[1] == 4) {
            try {
                PublicKey key = PublicKey.fromBytes(Arrays.copyOfRange(script, 1, 66));
                byte[] compressed = key.compressed();
                UtxoSnapshotWriter.writeVarInt(out, 4 | (compressed[0] & 1));
                out.write(compressed, 1, 32); return;
            } catch (IllegalArgumentException ignored) { /* invalid pubkeys use raw script encoding */ }
        }
        if (script.length > MAX_SCRIPT_SIZE) throw new IOException("Snapshot script exceeds maximum size");
        UtxoSnapshotWriter.writeVarInt(out, script.length + 6L);
        out.write(script);
    }

    static byte[] readScript(InputStream in) throws IOException {
        long code = readVarInt(in);
        if (code == 0 || code == 1) {
            byte[] h = readExactly(in, 20);
            if (code == 0) {
                byte[] s = new byte[25]; s[0]=0x76; s[1]=(byte)0xa9; s[2]=20; System.arraycopy(h,0,s,3,20); s[23]=(byte)0x88; s[24]=(byte)0xac; return s;
            }
            byte[] s = new byte[23]; s[0]=(byte)0xa9; s[1]=20; System.arraycopy(h,0,s,2,20); s[22]=(byte)0x87; return s;
        }
        if (code >= 2 && code <= 5) {
            byte[] x = readExactly(in, 32);
            byte prefix = (byte)(code <= 3 ? code : code - 2);
            byte[] compressed = new byte[33]; compressed[0]=prefix; System.arraycopy(x,0,compressed,1,32);
            final byte[] pub;
            try { pub = PublicKey.fromBytes(compressed).uncompressed(); }
            catch (IllegalArgumentException e) { throw new IOException("Invalid compressed pubkey in snapshot", e); }
            if (code <= 3) {
                byte[] s = new byte[35]; s[0]=33; System.arraycopy(compressed,0,s,1,33); s[34]=(byte)0xac; return s;
            }
            byte[] s = new byte[67]; s[0]=65; System.arraycopy(pub,0,s,1,65); s[66]=(byte)0xac; return s;
        }
        long size = code - 6;
        if (size < 0 || size > MAX_SCRIPT_SIZE) throw new IOException("Invalid snapshot script size: " + size);
        return readExactly(in, (int)size);
    }

    static long readVarInt(InputStream in) throws IOException {
        long n = 0;
        for (int i=0;i<10;i++) {
            int ch=in.read(); if(ch<0) throw new EOFException();
            if (n > (Long.MAX_VALUE >>> 7)) throw new IOException("VARINT overflow");
            n=(n<<7)|(ch&0x7f);
            if ((ch&0x80)!=0) { if (n==Long.MAX_VALUE) throw new IOException("VARINT overflow"); n++; }
            else return n;
        }
        throw new IOException("VARINT too long");
    }

    static long decompressAmount(long x) throws IOException {
        if (x == 0) return 0;
        x--;
        int e=(int)(x%10); x/=10;
        long n;
        if(e<9){ int d=(int)(x%9)+1; x/=9; n=Math.addExact(Math.multiplyExact(x,10),d); }
        else n=Math.addExact(x,1);
        try { while(e-- > 0) n=Math.multiplyExact(n,10); }
        catch(ArithmeticException ex){ throw new IOException("Compressed amount overflow",ex); }
        return n;
    }

    static byte[] readExactly(InputStream in,int n)throws IOException{ byte[] b=in.readNBytes(n); if(b.length!=n)throw new EOFException(); return b; }
    private static boolean isP2pkh(byte[] s){ return s.length==25&&s[0]==0x76&&s[1]==(byte)0xa9&&s[2]==20&&s[23]==(byte)0x88&&s[24]==(byte)0xac; }
    private static boolean isP2sh(byte[] s){ return s.length==23&&s[0]==(byte)0xa9&&s[1]==20&&s[22]==(byte)0x87; }
}
