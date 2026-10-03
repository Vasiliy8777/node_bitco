package ru.bitcoin.node.app.sync;

import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.consensus.pow.ChainWork;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.util.*;

/** Bounded low-work header presync commitments followed by mandatory redownload verification. */
final class HeadersPresyncState {
    private static final int PERIOD=2000;
    private final NetworkParameters parameters;
    private final BigInteger threshold;
    private BigInteger work;
    private Hash256 previous;
    private long count;
    private final List<byte[]> commitments=new ArrayList<>();
    private MessageDigest digest;
    HeadersPresyncState(NetworkParameters p, BigInteger startingWork, Hash256 previous){
        parameters=Objects.requireNonNull(p); threshold=p.minimumChainWork(); work=startingWork; this.previous=previous; digest=sha();
    }
    void accept(List<BlockHeader> headers){
        for(BlockHeader h:headers){
            if(!h.previousBlockHash().equals(previous)) throw new IllegalStateException("Low-work headers presync is not continuous");
            if(!ProofOfWork.isValid(h,parameters)) throw new IllegalStateException("Invalid proof of work during headers presync");
            digest.update(h.hash().bytes()); previous=h.hash(); work=ChainWork.add(work,ChainWork.blockWork(h.bits().value())); count++;
            if(count%PERIOD==0){commitments.add(digest.digest());digest=sha();}
        }
    }
    boolean reachedThreshold(){return work.compareTo(threshold)>=0;}
    long count(){return count;}
    List<byte[]> finish(){ if(count%PERIOD!=0) commitments.add(digest.digest()); return commitments.stream().map(byte[]::clone).toList(); }

    static final class Verifier {
        private final List<byte[]> expected; private int segment; private long count; private MessageDigest digest=sha();
        Verifier(List<byte[]> expected){this.expected=expected;}
        void accept(Hash256 hash){digest.update(hash.bytes());count++;if(count%PERIOD==0)verifySegment();}
        void finish(){if(count%PERIOD!=0)verifySegment();if(segment!=expected.size())throw new IllegalStateException("Headers commitment count mismatch");}
        private void verifySegment(){if(segment>=expected.size()||!java.util.Arrays.equals(expected.get(segment),digest.digest()))throw new IllegalStateException("Headers presync/redownload commitment mismatch at segment "+segment);segment++;digest=sha();}
    }

    static List<byte[]> commitments(List<Hash256> hashes){ MessageDigest d=sha(); List<byte[]> out=new ArrayList<>(); long n=0; for(Hash256 h:hashes){d.update(h.bytes());n++;if(n%PERIOD==0){out.add(d.digest());d=sha();}} if(n%PERIOD!=0)out.add(d.digest());return out; }
    private static MessageDigest sha(){try{return MessageDigest.getInstance("SHA-256");}catch(Exception e){throw new IllegalStateException(e);}}
}
