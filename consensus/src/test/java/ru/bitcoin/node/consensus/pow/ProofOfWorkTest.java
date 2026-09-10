package ru.bitcoin.node.consensus.pow;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.network.NetworkParametersRegistry;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

class ProofOfWorkTest {

    @Test
    void bitcoinGenesisBlockShouldSatisfyProofOfWork() {

        BlockHeader header =
                genesisHeader();

        assertTrue(
                ProofOfWork.isValid(
                        header,
                        NetworkParametersRegistry.mainnet()
                )
        );
    }

    @Test
    void changedNonceShouldUsuallyFailProofOfWork() {

        BlockHeader genesis =
                genesisHeader();

        BlockHeader changed =
                new BlockHeader(
                        genesis.version(),
                        genesis.previousBlockHash(),
                        genesis.merkleRoot(),
                        genesis.timestamp(),
                        genesis.bits(),
                        new UInt32(1)
                );

        assertFalse(
                ProofOfWork.isValid(
                        changed,
                        NetworkParametersRegistry.mainnet()
                )
        );
    }

    private static BlockHeader genesisHeader() {

        return new BlockHeader(
                1,
                Hash256.fromDisplayHex(
                        "0000000000000000000000000000000000000000000000000000000000000000"
                ),
                Hash256.fromDisplayHex(
                        "4a5e1e4baab89f3a32518a88c31bc87f" +
                                "618f76673e2cc77ab2127b7afdeda33b"
                ),
                new UInt32(1231006505L),
                new UInt32(0x1D00FFFFL),
                new UInt32(2083236893L)
        );
    }
    @Test
    void genesisHashShouldBeBelowTarget() {

        BlockHeader header =
                genesisHeader();

        BigInteger hash =
                ProofOfWork.hashToInteger(
                        header.hash()
                );

        BigInteger target =
                CompactTarget.decode(
                        header.bits().value()
                );

        assertTrue(
                hash.compareTo(target) <= 0
        );
    }
    @Test
    void shouldRejectTargetAboveNetworkPowLimit() {

        BlockHeader genesis =
                genesisHeader();

        BlockHeader invalid =
                new BlockHeader(
                        genesis.version(),
                        genesis.previousBlockHash(),
                        genesis.merkleRoot(),
                        genesis.timestamp(),

                        /*
                         * Искусственно значительно более
                         * лёгкий target.
                         */
                        new UInt32(0x207FFFFFL),

                        genesis.nonce()
                );

        assertFalse(
                ProofOfWork.isValid(
                        invalid,
                        NetworkParametersRegistry.mainnet()
                )
        );
    }
}
