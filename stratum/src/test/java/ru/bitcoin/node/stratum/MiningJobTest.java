package ru.bitcoin.node.stratum;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.pow.*;
import ru.bitcoin.node.crypto.hash.Hash256Digest;
import ru.bitcoin.node.crypto.merkle.MerkleTree;
import ru.bitcoin.node.mining.coinbase.CoinbaseBuilder;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.serialization.*;
import ru.bitcoin.node.protocol.transaction.*;
import ru.bitcoin.node.stratum.job.*;
import ru.bitcoin.node.stratum.protocol.StratumException;
import ru.bitcoin.node.stratum.share.ShareValidator;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MiningJobTest {
    @Test void notificationUsesStratumWordOrderAndLegacyCoinbaseHashWithWitnessPreservedInBlock() {
        for (int count : List.of(0, 1, 2, 4, 7)) {
            var work = work(count, 0, Hash256.fromDisplayHex("00".repeat(28) + "01020304"));
            var job = new MiningJob("job", work);
            var notify = job.notification(true);
            assertEquals("01020304" + "00".repeat(28), notify.get(1));
            assertEquals("20000000", notify.get(5));
            assertEquals("207fffff", notify.get(6));
            byte[] first = HexFormat.of().parseHex("0102030405060708");
            byte[] second = HexFormat.of().parseHex("1112131415161718");
            byte[] legacy = HexFormat.of().parseHex(notify.get(2) + HexFormat.of().formatHex(first)
                    + HexFormat.of().formatHex(second) + notify.get(3));
            var candidate = job.candidate(first, second, 1_800_000_000L, 7);
            assertFalse(TransactionParser.parse(legacy).hasWitness());
            assertTrue(candidate.coinbase().hasWitness());
            assertEquals(Hash256Digest.hash(legacy), candidate.coinbase().txId());
            assertArrayEquals(TransactionSerializer.serializeLegacy(candidate.coinbase()), legacy);
            var block = job.block(candidate);
            assertEquals(MerkleTree.calculateRoot(block.transactions().stream().map(Transaction::txId).toList()), candidate.header().merkleRoot());
            ru.bitcoin.node.consensus.block.WitnessCommitmentValidator.validate(block, true);
        }
    }

    @Test void sharesMustMeetTargetAndTimeLimitsButRealBlocksSurviveHarderShareDifficulty() {
        var job = new MiningJob("job", work(0, 0, new Hash256(new byte[32])));
        var validator = new ShareValidator(BigDecimal.ONE);
        MiningJob.Candidate valid = null;
        MiningJob.Candidate invalid = null;
        for (int nonce = 0; nonce < 1000 && (valid == null || invalid == null); nonce++) {
            var candidate = job.candidate(new byte[8], new byte[8], 1_800_000_000L, nonce);
            if (ProofOfWork.isValid(candidate.header(), NetworkParametersRegistry.regtest())) valid = candidate;
            else invalid = candidate;
        }
        assertNotNull(valid); assertNotNull(invalid);
        assertTrue(validator.validate(job, valid, 1_800_000_000L));
        var badShare = invalid;
        assertEquals(23, assertThrows(StratumException.class, () -> validator.validate(job, badShare, 1_800_000_000L)).code());
        assertFalse(new ShareValidator(new BigDecimal("0.0000000001")).validate(job, invalid, 1_800_000_000L));
        var tooOld = job.candidate(new byte[8], new byte[8], job.work().minimumTime() - 1, 0);
        assertEquals(20, assertThrows(StratumException.class, () -> validator.validate(job, tooOld, 1_800_000_000L)).code());
        var future = job.candidate(new byte[8], new byte[8], 1_800_007_201L, 0);
        assertEquals(20, assertThrows(StratumException.class, () -> validator.validate(job, future, 1_800_000_000L)).code());
        assertThrows(IllegalArgumentException.class, () -> new ShareValidator(BigDecimal.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new ShareValidator(new BigDecimal("1e999999")));
    }

    @Test void jobsAreBoundedAndInvalidatedOnParentChange() {
        var manager = new MiningJobManager();
        var parent = new Hash256(new byte[32]);
        var firstWork = work(0, 0, parent);
        var first = manager.update(firstWork);
        assertSame(first, manager.update(firstWork));
        manager.update(work(0, 1, parent));
        assertTrue(manager.find(first.id()).isPresent());
        for (int i = 2; i <= 8; i++) manager.update(work(0, i, parent));
        assertTrue(manager.find(first.id()).isEmpty());
        var current = manager.update(work(0, 9, parent));
        manager.update(work(0, 10, Hash256.fromDisplayHex("11".repeat(32))));
        assertTrue(manager.find(current.id()).isEmpty());
        manager.clear();
        var nonces = new ExtraNonceManager();
        assertEquals(1000, java.util.stream.IntStream.range(0, 1000).parallel().mapToObj(i -> nonces.next()).distinct().count());
    }

    private static MiningWork work(int count, long revision, Hash256 parent) {
        var selected = new ArrayList<Transaction>();
        for (int i = 0; i < count; i++) selected.add(new Transaction(2,
                List.of(new TxIn(new OutPoint(new Hash256(new byte[32]), new UInt32(i)), new byte[0], TxIn.FINAL_SEQUENCE,
                        new Witness(List.of(new byte[]{(byte)i})))), List.of(new TxOut(1000, new byte[]{0x51})), new UInt32(0)));
        var coinbase = CoinbaseBuilder.build(100, NetworkParametersRegistry.regtest(), 0, new byte[]{0x51}, new byte[16], selected);
        var transactions = new ArrayList<Transaction>(); transactions.add(coinbase); transactions.addAll(selected);
        var header = new BlockHeader(0x20000000, parent, MerkleTree.calculateRoot(transactions.stream().map(Transaction::txId).toList()),
                new UInt32(1_800_000_000L), new UInt32(0x207fffffL), new UInt32(0));
        return new MiningWork(new Block(header, transactions), 1_799_999_900L, revision, true);
    }
}
