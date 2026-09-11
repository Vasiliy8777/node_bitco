package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.block.BlockHeader;
import ru.bitcoin.node.storage.chain.ChainStateStore;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ChainStateManagerTest {

    @Test
    void shouldCommitAndPersistNewActiveTip() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisHeader()
                );

        BlockIndex child =
                BlockIndexFactory.createChild(
                        genesis,
                        childHeader(
                                genesis.hash()
                        )
                );

        ChainState chainState =
                new ChainState(
                        genesis
                );

        InMemoryChainStateStore store =
                new InMemoryChainStateStore();

        ChainStateManager manager =
                new ChainStateManager(
                        chainState,
                        store
                );

        ChainUpdate update =
                chainState.prepareUpdate(
                        child,
                        hash ->
                                hash.equals(genesis.hash())
                                        ? genesis
                                        : null
                );

        assertNotNull(update);

        manager.commit(
                update
        );

        assertEquals(
                child,
                chainState.activeTip()
        );

        assertEquals(
                child.hash(),
                store.loadActiveTipHash()
                        .orElseThrow()
        );
    }

    @Test
    void shouldRejectStaleUpdateBeforePersisting() {

        BlockIndex genesis =
                BlockIndexFactory.createGenesis(
                        genesisHeader()
                );

        BlockIndex child =
                BlockIndexFactory.createChild(
                        genesis,
                        childHeader(
                                genesis.hash()
                        )
                );

        ChainState chainState =
                new ChainState(
                        genesis
                );

        InMemoryChainStateStore store =
                new InMemoryChainStateStore();

        ChainStateManager manager =
                new ChainStateManager(
                        chainState,
                        store
                );

        ChainUpdate update =
                chainState.prepareUpdate(
                        child,
                        hash ->
                                hash.equals(genesis.hash())
                                        ? genesis
                                        : null
                );

        assertNotNull(update);

        manager.commit(
                update
        );

        assertThrows(
                IllegalStateException.class,
                () ->
                        manager.commit(
                                update
                        )
        );

        assertEquals(
                child.hash(),
                store.loadActiveTipHash()
                        .orElseThrow()
        );
    }

    private static BlockHeader genesisHeader() {

        return new BlockHeader(
                1,
                Hash256.fromDisplayHex(
                        "00000000000000000000000000000000" +
                                "00000000000000000000000000000000"
                ),
                Hash256.fromDisplayHex(
                        "4a5e1e4baab89f3a32518a88c31bc87" +
                                "f618f76673e2cc77ab2127b7afdeda33b"
                ),
                new UInt32(
                        1231006505L
                ),
                new UInt32(
                        0x1D00FFFFL
                ),
                new UInt32(
                        2083236893L
                )
        );
    }

    private static BlockHeader childHeader(
            Hash256 previousBlockHash
    ) {

        return new BlockHeader(
                1,
                previousBlockHash,
                Hash256.fromDisplayHex(
                        "11111111111111111111111111111111" +
                                "11111111111111111111111111111111"
                ),
                new UInt32(
                        1231007100L
                ),
                new UInt32(
                        0x1D00FFFFL
                ),
                new UInt32(
                        1L
                )
        );
    }

    private static final class InMemoryChainStateStore
            implements ChainStateStore {

        private Hash256 activeTipHash;

        @Override
        public Optional<Hash256> loadActiveTipHash() {
            return Optional.ofNullable(
                    activeTipHash
            );
        }

        @Override
        public void saveActiveTipHash(
                Hash256 hash
        ) {
            this.activeTipHash =
                    hash;
        }
    }
}