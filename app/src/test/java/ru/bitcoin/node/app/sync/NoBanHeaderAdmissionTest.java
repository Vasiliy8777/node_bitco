package ru.bitcoin.node.app.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import ru.bitcoin.node.common.types.*;
import ru.bitcoin.node.consensus.pow.ProofOfWork;
import ru.bitcoin.node.p2p.*;
import ru.bitcoin.node.p2p.message.HeadersMessage;
import ru.bitcoin.node.protocol.block.*;
import ru.bitcoin.node.protocol.network.*;
import ru.bitcoin.node.storage.rocksdb.RocksDbDatabase;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NoBanHeaderAdmissionTest {
    @TempDir Path directory;
    private static final NetworkParameters NETWORK = NetworkParametersRegistry.regtest();
    private static final BlockHeader GENESIS = GenesisBlockFactory.create(NETWORK).header();
    private static final Hash256 ZERO = new Hash256(new byte[32]);

    @Test void onlyNoBanBypassesPresyncAndStillUsesContextualValidation() throws Exception {
        var parameters = spy(NETWORK);
        doReturn(BigInteger.valueOf(9000)).when(parameters).minimumChainWork();
        try (var db = new RocksDbDatabase(directory)) {
            var validation = new ru.bitcoin.node.app.NodeValidationService(db, parameters,
                    () -> 1_800_000_000L, new ru.bitcoin.node.mempool.Mempool());
            var sync = new NodeSyncInfrastructure(db, parameters, () -> 1_800_000_000L);
            var service = sync.headerSyncService();
            var header = mine(GENESIS.hash(), GENESIS.timestamp().value() + 1);
            for (var permission : List.of(PeerPermission.DOWNLOAD)) {
                var ordinary = mock(Peer.class); when(ordinary.hasPermission(permission)).thenReturn(true);
                assertTrue(service.processPeerWithReception(ordinary, new HeadersMessage(List.of(header)), ignored -> {}, () -> {}).reception().indexes().isEmpty());
                assertNull(sync.blockIndexLookup().find(header.hash()));
            }
            var trusted = mock(Peer.class); when(trusted.hasPermission(PeerPermission.NOBAN)).thenReturn(true);
            var contextInvalid = mine(GENESIS.hash(), GENESIS.timestamp().value());
            assertThrows(RuntimeException.class, () -> service.processPeerWithReception(trusted, new HeadersMessage(List.of(contextInvalid)), ignored -> {}, () -> {}));
            assertNull(sync.blockIndexLookup().find(contextInvalid.hash()));
            var unknown = mine(ZERO, GENESIS.timestamp().value() + 1);
            assertThrows(RuntimeException.class, () -> service.processPeerWithReception(trusted, new HeadersMessage(List.of(unknown)), ignored -> {}, () -> {}));
            var invalidPow = new BlockHeader(4, GENESIS.hash(), ZERO, new UInt32(GENESIS.timestamp().value() + 1), new UInt32(0), new UInt32(0));
            assertThrows(RuntimeException.class, () -> service.processPeerWithReception(trusted, new HeadersMessage(List.of(invalidPow)), ignored -> {}, () -> {}));
            assertEquals(1, service.processPeerWithReception(trusted, new HeadersMessage(List.of(header)), ignored -> {}, () -> {}).reception().indexes().size());
            assertEquals(1, sync.blockIndexLookup().find(header.hash()).height());
            assertEquals(0, validation.activeTip().height());
            validation.close();
        }
    }

    private static BlockHeader mine(Hash256 parent, long time) {
        for (long nonce = 0; nonce < 10000; nonce++) {
            var header = new BlockHeader(4, parent, ZERO, new UInt32(time), new UInt32(0x207fffffL), new UInt32(nonce));
            if (ProofOfWork.isValid(header, NETWORK)) return header;
        }
        throw new AssertionError("Regtest mining failed");
    }
}
