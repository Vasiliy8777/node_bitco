package ru.bitcoin.node.protocol.address;

import ru.bitcoin.node.crypto.hash.Hash160;
import ru.bitcoin.node.crypto.secp256k1.PrivateKey;
import ru.bitcoin.node.crypto.secp256k1.Secp256k1;
import ru.bitcoin.node.protocol.network.BitcoinNetwork;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Immutable BIP32 extended key; private and public derivation have Core's failure semantics. */
public final class Bip32Key {
    private final int depth;
    private final long child;
    private final byte[] parentFingerprint, chainCode, publicKey;
    private final BigInteger secret;
    private final boolean mainnet;

    private Bip32Key(int depth,long child,byte[] fingerprint,byte[] chainCode,byte[] publicKey,BigInteger secret,boolean mainnet) {
        this.depth=depth;this.child=child;this.parentFingerprint=fingerprint.clone();this.chainCode=chainCode.clone();
        this.publicKey=publicKey.clone();this.secret=secret;this.mainnet=mainnet;
    }
    public static Bip32Key fromSeed(byte[] seed,boolean mainnet) {
        if(seed.length<16||seed.length>64)throw new IllegalArgumentException("BIP32 seed must contain 16..64 bytes");
        byte[] material=hmac("Bitcoin seed".getBytes(StandardCharsets.US_ASCII),seed);
        try {
            var key=PrivateKey.fromBytes(Arrays.copyOf(material,32));
            return new Bip32Key(0,0,new byte[4],Arrays.copyOfRange(material,32,64),Secp256k1.publicKey(key).compressed(),key.value(),mainnet);
        } finally { Arrays.fill(material,(byte)0); }
    }
    /** Public root used by BIP328; no secret key exists for the aggregate. */
    public static Bip32Key publicRoot(byte[] publicKey,byte[] chainCode,boolean mainnet) {
        if(chainCode.length!=32||publicKey.length!=33||(publicKey[0]!=2&&publicKey[0]!=3))throw new IllegalArgumentException("Invalid public root");
        ru.bitcoin.node.crypto.secp256k1.PublicKey.fromBytes(publicKey);
        return new Bip32Key(0,0,new byte[4],chainCode,publicKey,null,mainnet);
    }
    public static Bip32Key decode(String encoded,NetworkParameters network) {
        byte[] bytes=Base58Check.decode(encoded,78);
        try {
            if(bytes.length!=78)throw new IllegalArgumentException("Invalid extended key size");
            boolean mainnet=network.network()==BitcoinNetwork.MAINNET;
            long prefix=read32(bytes,0);boolean privateKey=prefix==(mainnet?0x0488_ade4L:0x0435_8394L);
            if(!privateKey&&prefix!=(mainnet?0x0488_b21eL:0x0435_87cfL))throw new IllegalArgumentException("Invalid extended key version");
            int depth=bytes[4]&255;long child=read32(bytes,9);byte[] fingerprint=Arrays.copyOfRange(bytes,5,9);
            if(depth==0&&(child!=0||!Arrays.equals(fingerprint,new byte[4])))throw new IllegalArgumentException("Invalid root key metadata");
            byte[] chain=Arrays.copyOfRange(bytes,13,45),pub;BigInteger secret=null;
            if(privateKey) {
                if(bytes[45]!=0)throw new IllegalArgumentException("Invalid private key marker");
                var key=PrivateKey.fromBytes(Arrays.copyOfRange(bytes,46,78));secret=key.value();pub=Secp256k1.publicKey(key).compressed();
            } else {
                pub=Arrays.copyOfRange(bytes,45,78);
                if(pub[0]!=2&&pub[0]!=3)throw new IllegalArgumentException("Invalid compressed public key");
                ru.bitcoin.node.crypto.secp256k1.PublicKey.fromBytes(pub);
            }
            return new Bip32Key(depth,child,fingerprint,chain,pub,secret,mainnet);
        } finally { Arrays.fill(bytes,(byte)0); }
    }
    public Optional<Bip32Key> derive(long index) {
        if(index<0||index>0xffff_ffffL||depth==255||index>=0x8000_0000L&&secret==null)return Optional.empty();
        byte[] data=new byte[37];
        if(index>=0x8000_0000L)System.arraycopy(new PrivateKey(secret).bytes(),0,data,1,32);
        else System.arraycopy(publicKey,0,data,0,33);
        write32(data,33,index);byte[] material=hmac(chainCode,data);Arrays.fill(data,(byte)0);
        try {
            BigInteger tweak=new BigInteger(1,Arrays.copyOf(material,32));
            if(tweak.compareTo(Secp256k1.N)>=0)return Optional.empty();
            BigInteger derivedSecret=secret==null?null:secret.add(tweak).mod(Secp256k1.N);byte[] derivedPublic;
            if(derivedSecret!=null) {
                if(derivedSecret.signum()==0)return Optional.empty();
                derivedPublic=Secp256k1.publicKey(new PrivateKey(derivedSecret)).compressed();
            } else {
                var point=Secp256k1.DOMAIN.getCurve().decodePoint(publicKey).add(Secp256k1.DOMAIN.getG().multiply(tweak)).normalize();
                if(point.isInfinity())return Optional.empty();derivedPublic=point.getEncoded(true);
            }
            return Optional.of(new Bip32Key(depth+1,index,fingerprint(),Arrays.copyOfRange(material,32,64),derivedPublic,derivedSecret,mainnet));
        } finally { Arrays.fill(material,(byte)0); }
    }
    public Bip32Key neuter() { return new Bip32Key(depth,child,parentFingerprint,chainCode,publicKey,null,mainnet); }
    public Bip32Key withPrivateKey(PrivateKey key) {
        if(!Arrays.equals(publicKey,Secp256k1.publicKey(key).compressed()))throw new IllegalArgumentException("Private key does not match extended public key");
        return new Bip32Key(depth,child,parentFingerprint,chainCode,publicKey,key.value(),mainnet);
    }
    public Optional<PrivateKey> privateKey() { return secret==null?Optional.empty():Optional.of(new PrivateKey(secret)); }
    public byte[] publicKey() { return publicKey.clone(); }
    public byte[] fingerprint() { return Arrays.copyOf(Hash160.hash(publicKey),4); }
    public int depth() { return depth; }
    public String encode() {
        byte[] bytes=new byte[78];write32(bytes,0,secret==null?(mainnet?0x0488_b21eL:0x0435_87cfL):(mainnet?0x0488_ade4L:0x0435_8394L));
        bytes[4]=(byte)depth;System.arraycopy(parentFingerprint,0,bytes,5,4);write32(bytes,9,child);System.arraycopy(chainCode,0,bytes,13,32);
        if(secret==null)System.arraycopy(publicKey,0,bytes,45,33);
        else System.arraycopy(new PrivateKey(secret).bytes(),0,bytes,46,32);
        try { return BitcoinAddressEncoder.legacy(bytes[0]&255,Arrays.copyOfRange(bytes,1,bytes.length)); }
        finally { Arrays.fill(bytes,(byte)0); }
    }
    private static byte[] hmac(byte[] key,byte[] message) {
        try { var mac=Mac.getInstance("HmacSHA512");mac.init(new SecretKeySpec(key,"HmacSHA512"));return mac.doFinal(message); }
        catch(java.security.GeneralSecurityException unavailable) { throw new IllegalStateException(unavailable); }
    }
    private static long read32(byte[] bytes,int offset) { long value=0;for(int i=0;i<4;i++)value=value<<8|(bytes[offset+i]&255);return value; }
    private static void write32(byte[] bytes,int offset,long value) { for(int i=0;i<4;i++)bytes[offset+i]=(byte)(value>>>(24-8*i)); }
}
