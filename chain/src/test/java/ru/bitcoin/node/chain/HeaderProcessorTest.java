package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.consensus.time.AdjustedTime;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HeaderProcessorTest {

    private static final NetworkParameters REGTEST =
            NetworkParametersRegistry.regtest();

    private static final AdjustedTime TEST_TIME =
            () -> 1_800_000_000L;

    @Test
    void shouldProcessValidChildHeader() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        BlockIndex parent =
                createParent();

        indexes.put(
                parent.hash(),
                parent
        );

        HeaderProcessor processor =
                new HeaderProcessor(
                        indexes::get,
                        REGTEST,
                        TEST_TIME
                );

        BlockHeader child =
                findValidHeader(
                        parent.hash(),
                        parent.header()
                                .timestamp()
                                .value() + 1
                );

        BlockIndex result =
                processor.process(
                        child
                );

        assertEquals(
                child.hash(),
                result.hash()
        );

        assertEquals(
                child,
                result.header()
        );

        assertEquals(
                1L,
                result.height()
        );

        assertEquals(
                parent.hash(),
                result.previousBlockHash()
        );

        assertTrue(
                result.chainWork()
                        .compareTo(
                                parent.chainWork()
                        ) > 0
        );

        /*
         * HeaderProcessor must use exactly the same
         * BlockIndex construction rules as the rest
         * of the chain module.
         */
        BlockIndex expected =
                BlockIndexFactory.createChild(
                        parent,
                        child
                );

        assertEquals(
                expected.chainWork(),
                result.chainWork()
        );
    }

    @Test
    void shouldReturnExistingIndex() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        BlockIndex existing =
                createParent();

        indexes.put(
                existing.hash(),
                existing
        );

        HeaderProcessor processor =
                new HeaderProcessor(
                        indexes::get,
                        REGTEST,
                        TEST_TIME
                );

        BlockIndex result =
                processor.process(
                        existing.header()
                );

        assertSame(
                existing,
                result
        );
    }

    @Test
    void shouldRejectUnknownParent() {

        Map<Hash256, BlockIndex> indexes =
                new HashMap<>();

        HeaderProcessor processor =
                new HeaderProcessor(
                        indexes::get,
                        REGTEST,
                        TEST_TIME
                );

        Hash256 unknownParent =
                Hash256.fromDisplayHex(
                        "22".repeat(32)
                );

        BlockHeader header =
                findValidHeader(
                        unknownParent,
                        1_700_000_001L
                );

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        () -> processor.process(
                                header
                        )
                );

        assertTrue(
                exception.getMessage()
                        .contains(
                                unknownParent.toDisplayHex()
                        )
        );
    }

    @Test
    void shouldRejectNullHeader() {

        HeaderProcessor processor =
                new HeaderProcessor(
                        hash -> null,
                        REGTEST,
                        TEST_TIME
                );

        assertThrows(
                IllegalArgumentException.class,
                () -> processor.process(
                        null
                )
        );
    }

    private static BlockIndex createParent() {

        Hash256 previous =
                Hash256.fromDisplayHex(
                        "00".repeat(32)
                );

        BlockHeader header =
                findValidHeader(
                        previous,
                        1_700_000_000L
                );

        return new BlockIndex(
                header.hash(),
                header,
                0,
                previous,
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
                            new UInt32(
                                    timestamp
                            ),
                            new UInt32(
                                    0x207fffffL
                            ),
                            new UInt32(
                                    nonce
                            )
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
}