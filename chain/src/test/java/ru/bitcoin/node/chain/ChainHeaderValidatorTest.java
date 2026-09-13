package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.block.BlockHeaderValidationException;
import ru.bitcoin.node.consensus.pow.CompactTarget;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ChainHeaderValidatorTest {

    private static final AdjustedTime TEST_TIME =
            () -> 1_800_000_000L;
    private static final NetworkParameters REGTEST =
            NetworkParametersRegistry.regtest();
    private static final NetworkParameters REAL_TESTNET =
            NetworkParametersRegistry.testnet();

    private static final NetworkParameters TESTNET =
            new NetworkParameters(
                    REAL_TESTNET.network(),
                    0x0709110BL,
                    18333,
                    REAL_TESTNET.genesisBlockHash(),
                    REAL_TESTNET.bip16ExceptionBlockHash(),
                    new BigInteger(
                            "00000000ffffffffffffffffffffffffffffffffffffffffffffffffffffffff",
                            16
                    ),
                    600L,
                    14L * 24L * 60L * 60L,
                    REAL_TESTNET.subsidyHalvingInterval(),
                    REAL_TESTNET.bip34Height(),
                    REAL_TESTNET.bip66Height(),
                    REAL_TESTNET.bip65Height(),
                    REAL_TESTNET.csvHeight(),
                    REAL_TESTNET.segwitHeight(),
                    true,
                    false,
                    false
            );

    @Test
    void testnetParametersShouldBeConfiguredCorrectly() {
        assertTrue(
                TESTNET.allowMinDifficultyBlocks()
        );

        assertFalse(
                TESTNET.enforceBip94()
        );

        assertFalse(
                TESTNET.noRetargeting()
        );

        assertEquals(
                0x1d00ffffL,
                CompactTarget.encode(
                        TESTNET.powLimit()
                )
        );
    }

    @Test
    void shouldValidateHeaderAgainstParentChain() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        BlockIndex parent =
                createParent();

        indexes.put(
                parent.hash(),
                parent
        );

        BlockHeader candidate =
                findValidHeader(
                        parent.hash(),
                        parent.header()
                                .timestamp()
                                .value() + 1
                );

        assertDoesNotThrow(
                () -> ChainHeaderValidator.validate(
                        candidate,
                        parent,
                        indexes::get,
                        REGTEST,
                        TEST_TIME
                )
        );
    }

    @Test
    void shouldRejectWrongParent() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        BlockIndex parent =
                createParent();

        indexes.put(
                parent.hash(),
                parent
        );

        BlockHeader candidate =
                findValidHeader(
                        Hash256.fromDisplayHex(
                                "99".repeat(32)
                        ),
                        parent.header()
                                .timestamp()
                                .value() + 1
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> ChainHeaderValidator.validate(
                        candidate,
                        parent,
                        indexes::get,
                        REGTEST,
                        TEST_TIME
                )
        );
    }

    @Test
    void shouldRejectTimestampNotGreaterThanMtp() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        BlockIndex parent =
                createParent();

        indexes.put(
                parent.hash(),
                parent
        );

        BlockHeader candidate =
                findValidHeader(
                        parent.hash(),
                        parent.header()
                                .timestamp()
                                .value()
                );

        assertThrows(
                BlockHeaderValidationException.class,
                () -> ChainHeaderValidator.validate(
                        candidate,
                        parent,
                        indexes::get,
                        REGTEST,
                        TEST_TIME
                )
        );
    }

    private static BlockIndex createParent() {

        BlockHeader header =
                findValidHeader(
                        Hash256.fromDisplayHex(
                                "00".repeat(32)
                        ),
                        1_700_000_000L
                );

        return new BlockIndex(
                header.hash(),
                header,
                0,
                Hash256.fromDisplayHex(
                        "00".repeat(32)
                ),
                BigInteger.ONE
        );
    }

    private static BlockHeader findValidHeader(
            Hash256 previousBlockHash,
            long timestamp
    ) {

        for (long nonce = 0;
             nonce <= UInt32.MAX_VALUE;
             nonce++) {

            BlockHeader header =
                    new BlockHeader(
                            4,
                            previousBlockHash,
                            Hash256.fromDisplayHex(
                                    "11".repeat(32)
                            ),
                            new UInt32(timestamp),
                            new UInt32(
                                    0x207fffffL
                            ),
                            new UInt32(nonce)
                    );

            if (ProofOfWork.isValid(
                    header,
                    REGTEST
            )) {
                return header;
            }
        }

        throw new IllegalStateException(
                "Could not find valid regtest nonce"
        );
    }
    @Test
    void shouldUsePowLimitAfterTestnetTimeGap() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        BlockIndex parent =
                createIndexWithBits(
                        null,
                        100,
                        1_700_000_000L,
                        0x1c0fffffL
                );

        indexes.put(
                parent.hash(),
                parent
        );

        BlockHeader candidate =
                headerWithBits(
                        parent.hash(),
                        1_700_001_201L,
                        CompactTarget.encode(
                                TESTNET.powLimit()
                        ),
                        1
                );

        UInt32 expected =
                ChainHeaderValidator
                        .calculateExpectedBits(
                                101,
                                candidate,
                                parent,
                                indexes::get,
                                TESTNET
                        );

        assertEquals(
                CompactTarget.encode(
                        TESTNET.powLimit()
                ),
                expected.value()
        );
    }
    @Test
    void shouldNotUseMinDifficultyAtExactThreshold() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        BlockIndex parent =
                createIndexWithBits(
                        null,
                        100,
                        1_700_000_000L,
                        0x1c0fffffL
                );

        indexes.put(
                parent.hash(),
                parent
        );

        BlockHeader candidate =
                headerWithBits(
                        parent.hash(),
                        1_700_001_200L,
                        0x1c0fffffL,
                        1
                );

        UInt32 expected =
                ChainHeaderValidator
                        .calculateExpectedBits(
                                101,
                                candidate,
                                parent,
                                indexes::get,
                                TESTNET
                        );

        assertEquals(
                0x1c0fffffL,
                expected.value()
        );
    }
    @Test
    void shouldRestoreLastNonMinDifficultyBits() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        long normalBits =
                0x1c0fffffL;

        long minBits =
                CompactTarget.encode(
                        TESTNET.powLimit()
                );

        BlockIndex normal =
                createIndexWithBits(
                        null,
                        100,
                        1_700_000_000L,
                        normalBits
                );

        indexes.put(
                normal.hash(),
                normal
        );

        BlockIndex min1 =
                createIndexWithBits(
                        normal,
                        101,
                        1_700_002_000L,
                        minBits
                );

        indexes.put(
                min1.hash(),
                min1
        );

        BlockIndex min2 =
                createIndexWithBits(
                        min1,
                        102,
                        1_700_002_600L,
                        minBits
                );

        indexes.put(
                min2.hash(),
                min2
        );

        /*
         * Candidate timestamp НЕ превышает
         * min2.time + 1200.
         *
         * Поэтому expected bits должны откатиться
         * назад через min2/min1 к normal.
         */
        BlockHeader candidate =
                headerWithBits(
                        min2.hash(),
                        1_700_003_000L,
                        normalBits,
                        5
                );

        UInt32 expected =
                ChainHeaderValidator
                        .calculateExpectedBits(
                                103,
                                candidate,
                                min2,
                                indexes::get,
                                TESTNET
                        );

        assertEquals(
                normalBits,
                expected.value()
        );
    }
    private static BlockIndex createIndexWithBits(
            BlockIndex parent,
            long height,
            long timestamp,
            long bits
    ) {
        Hash256 previousHash =
                parent == null
                        ? Hash256.fromDisplayHex(
                        "00".repeat(32)
                )
                        : parent.hash();

        BlockHeader header =
                headerWithBits(
                        previousHash,
                        timestamp,
                        bits,
                        height
                );

        return new BlockIndex(
                header.hash(),
                header,
                height,
                previousHash,
                BigInteger.valueOf(
                        height + 1
                )
        );
    }

    private static BlockHeader headerWithBits(
            Hash256 previousHash,
            long timestamp,
            long bits,
            long nonce
    ) {
        return new BlockHeader(
                1,
                previousHash,
                Hash256.fromDisplayHex(
                        "22".repeat(32)
                ),
                new UInt32(timestamp),
                new UInt32(bits),
                new UInt32(nonce)
        );
    }
}