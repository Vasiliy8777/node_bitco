package ru.bitcoin.node.chain;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.storage.block.BlockIndexStore;
import ru.bitcoin.node.storage.block.StoredBlockIndex;
import ru.bitcoin.node.storage.chain.ChainStateStore;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ChainStateLoaderTest {

    @Test
    void shouldReturnEmptyWhenActiveTipDoesNotExist() {

        InMemoryBlockIndexStore blockIndexStore =
                new InMemoryBlockIndexStore();

        InMemoryChainStateStore chainStateStore =
                new InMemoryChainStateStore();

        ChainStateLoader loader =
                new ChainStateLoader(
                        blockIndexStore,
                        chainStateStore
                );

        Optional<ChainState> result =
                loader.load();

        assertTrue(
                result.isEmpty()
        );
    }

    @Test
    void shouldRestoreChainStateFromStoredActiveTip() {

        BlockHeader header =
                genesisHeader();

        BlockIndex original =
                BlockIndexFactory
                        .createGenesis(
                                header
                        );

        InMemoryBlockIndexStore blockIndexStore =
                new InMemoryBlockIndexStore();

        blockIndexStore.save(
                BlockIndexStorageMapper
                        .toStored(original)
        );

        InMemoryChainStateStore chainStateStore =
                new InMemoryChainStateStore();

        chainStateStore.saveActiveTipHash(
                original.hash()
        );

        ChainStateLoader loader =
                new ChainStateLoader(
                        blockIndexStore,
                        chainStateStore
                );

        ChainState restored =
                loader.load()
                        .orElseThrow();

        assertEquals(
                original,
                restored.activeTip()
        );

        assertEquals(
                original.hash(),
                restored.activeTip().hash()
        );

        assertEquals(
                original.height(),
                restored.activeTip().height()
        );

        assertEquals(
                original.chainWork(),
                restored.activeTip().chainWork()
        );
    }

    @Test
    void shouldRejectMissingActiveTipBlockIndex() {

        Hash256 missingHash =
                Hash256.fromDisplayHex(
                        "11111111111111111111111111111111" +
                                "11111111111111111111111111111111"
                );

        InMemoryBlockIndexStore blockIndexStore =
                new InMemoryBlockIndexStore();

        InMemoryChainStateStore chainStateStore =
                new InMemoryChainStateStore();

        chainStateStore.saveActiveTipHash(
                missingHash
        );

        ChainStateLoader loader =
                new ChainStateLoader(
                        blockIndexStore,
                        chainStateStore
                );

        IllegalStateException exception =
                assertThrows(
                        IllegalStateException.class,
                        loader::load
                );

        assertTrue(
                exception.getMessage()
                        .contains(
                                missingHash.toDisplayHex()
                        )
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
                        "4a5e1e4baab89f3a32518a88c31bc87f" +
                                "618f76673e2cc77ab2127b7afdeda33b"
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

    private static final class InMemoryBlockIndexStore
            implements BlockIndexStore {

        private final Map<Hash256, StoredBlockIndex> values =
                new HashMap<>();

        @Override
        public void save(
                StoredBlockIndex blockIndex
        ) {
            values.put(
                    blockIndex.hash(),
                    blockIndex
            );
        }

        @Override
        public Optional<StoredBlockIndex> find(
                Hash256 hash
        ) {
            return Optional.ofNullable(
                    values.get(hash)
            );
        }

        @Override
        public void delete(
                Hash256 hash
        ) {
            values.remove(hash);
        }
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

        @Override
        public Optional<Hash256> loadBestHeaderTipHash() {
            return Optional.empty();
        }

        @Override
        public void saveBestHeaderTipHash(Hash256 hash) {

        }
    }
}