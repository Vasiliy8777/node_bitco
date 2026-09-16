package ru.bitcoin.node.mining;

import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.CompactTarget;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Bounded CPU nonce search. A result still requires contextual block admission. */
public final class NonceMiner {
    private NonceMiner() { }
    public static Optional<Block> search(Block candidate, NetworkParameters parameters,
                                         long firstNonce, long attempts, BooleanSupplier cancelled) {
        Objects.requireNonNull(candidate); Objects.requireNonNull(parameters); Objects.requireNonNull(cancelled);
        if(firstNonce<0 || firstNonce>0xffffffffL || attempts<0 || attempts>0x1_0000_0000L-firstNonce)
            throw new IllegalArgumentException("Nonce range exceeds uint32");
        var source=candidate.header();
        var target=CompactTarget.decode(source.bits().value());
        if(target.signum()<=0 || target.compareTo(parameters.powLimit())>0)
            throw new IllegalArgumentException("Invalid proof-of-work target");
        for(long n=firstNonce;n<firstNonce+attempts;n++) {
            if(Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) return Optional.empty();
            var header=new BlockHeader(source.version(),source.previousBlockHash(),source.merkleRoot(),
                    source.timestamp(),source.bits(),new UInt32(n));
            if(ProofOfWork.isValid(header,parameters)) return Optional.of(new Block(header,candidate.transactions()));
        }
        return Optional.empty();
    }
}
