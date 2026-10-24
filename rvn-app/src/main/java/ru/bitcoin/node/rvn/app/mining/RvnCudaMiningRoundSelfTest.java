package ru.bitcoin.node.rvn.app.mining;

import ru.bitcoin.node.rvn.chain.*;
import ru.bitcoin.node.rvn.consensus.*;
import ru.bitcoin.node.rvn.pow.*;
import java.io.IOException;
import java.math.BigInteger;
import java.util.*;

/** Actual GPU job -> proof installation -> independent CPU/native validation.
 * Synthetic easy target, memory-only admission, no peers and no mainnet reward. */
public final class RvnCudaMiningRoundSelfTest {
    private RvnCudaMiningRoundSelfTest() {}
    public static void run(RvnCudaKawpowWorker worker, RvnPayoutAddress payout) throws IOException {
        final long timestamp = 1600000001L;
        var template = RvnBlockTemplateBuilder.build(0x30000000, new byte[32], 1, timestamp,
                0x207fffffL, 1, payout, List.of(), 0, true);
        var limit = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.ONE);
        var job = new RvnSoloMining.Job(1, template, limit, timestamp + 30);
        var cpu = new RvnKawpowLightEngine(1024);
        boolean[] accepted = {false}, relayed = {false};
        var backend = new RvnSoloMining.Backend() {
            public Optional<RvnSoloMining.Job> next() { return accepted[0] ? Optional.empty() : Optional.of(job); }
            public boolean current(RvnSoloMining.Job value) { return !accepted[0] && value == job; }
            public Optional<RvnSoloMining.Accepted> accept(RvnSoloMining.Job value, RvnBlock block) throws IOException {
                if (!current(value)) return Optional.empty();
                if (!Arrays.equals(template.header().kawpowInputDigest(), block.header().kawpowInputDigest())
                        || !Arrays.equals(RvnBlockCodec.serialize(template),
                                RvnBlockCodec.serialize(new RvnBlock(template.header(), block.transactions()))))
                    throw new IOException("GPU self-test candidate changed template");
                try {
                    var proof = RvnKawpowProof.requireValidMix(cpu, block.header().serialize());
                    if (!RvnPowDigest.meetsTarget(proof.powDigestWire(), RvnPowDigest.ByteOrder.LITTLE_ENDIAN,
                            block.header().compactBits(), limit)) throw new IOException("GPU self-test proof missed target");
                } catch (RvnPowException e) { throw new IOException("GPU self-test CPU verification failed", e); }
                RvnNativeTransactionChecks.block(block, new RvnSpendContext(1, timestamp - 1, timestamp - 1,
                        false, 0, h -> timestamp - 1), true, point -> Optional.empty());
                if (!Arrays.equals(block.transactions().getFirst().outputs().getFirst().scriptPubKey(), payout.scriptPubKey()))
                    throw new IOException("GPU self-test payout mismatch");
                accepted[0] = true;
                return Optional.of(new RvnSoloMining.Accepted(block));
            }
            public void relay(RvnSoloMining.Accepted block) {
                if (!accepted[0]) throw new IllegalStateException("Self-test relayed before validation");
                relayed[0] = true; // Deliberately no external network relay.
            }
        };
        var miner = new RvnSoloMining(backend, worker::search, () -> timestamp, 4);
        for (int i = 0; i < 16 && !relayed[0]; i++) miner.advance();
        if (!accepted[0] || !relayed[0]) throw new IOException("GPU job self-test did not find and validate a candidate");
    }
}
