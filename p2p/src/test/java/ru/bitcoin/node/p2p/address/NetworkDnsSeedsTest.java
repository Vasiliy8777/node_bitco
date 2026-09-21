package ru.bitcoin.node.p2p.address;

import org.junit.jupiter.api.Test;
import ru.bitcoin.node.protocol.network.BitcoinNetwork;

import static org.junit.jupiter.api.Assertions.*;

class NetworkDnsSeedsTest {

    @Test
    void shouldProvideMainnetSeeds() {

        assertEquals(
                7,
                NetworkDnsSeeds.forNetwork(
                        BitcoinNetwork.MAINNET
                ).size()
        );

        assertTrue(
                NetworkDnsSeeds.forNetwork(
                        BitcoinNetwork.MAINNET
                ).contains(
                        "dnsseed.bluematt.me"
                )
        );
    }

    @Test
    void shouldProvideTestnetSeeds() {

        assertEquals(
                4,
                NetworkDnsSeeds.forNetwork(
                        BitcoinNetwork.TESTNET
                ).size()
        );

        assertTrue(
                NetworkDnsSeeds.forNetwork(
                        BitcoinNetwork.TESTNET
                ).contains(
                        "testnet-seed.bitcoin.jonasschnelli.ch"
                )
        );
    }

    @Test
    void shouldProvideSignetSeeds() {

        assertEquals(
                2,
                NetworkDnsSeeds.forNetwork(
                        BitcoinNetwork.SIGNET
                ).size()
        );
    }

    @Test
    void shouldNotProvidePublicDnsSeedsForRegtest() {

        assertTrue(
                NetworkDnsSeeds.forNetwork(
                        BitcoinNetwork.REGTEST
                ).isEmpty()
        );
    }
}