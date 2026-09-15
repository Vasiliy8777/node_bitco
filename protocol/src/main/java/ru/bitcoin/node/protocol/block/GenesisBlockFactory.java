package ru.bitcoin.node.protocol.block;

import ru.bitcoin.node.common.bytes.HexUtils;
import ru.bitcoin.node.common.types.Hash256;
import ru.bitcoin.node.common.types.UInt32;
import ru.bitcoin.node.protocol.network.NetworkParameters;
import ru.bitcoin.node.protocol.transaction.*;

import java.util.List;
import java.util.Objects;

/** Genesis constants from Bitcoin Core src/kernel/chainparams.cpp. */
public final class GenesisBlockFactory {
    private GenesisBlockFactory() {
    }

    public static Block create(NetworkParameters parameters) {
        Objects.requireNonNull(parameters, "parameters");
        Transaction coinbase = new Transaction(1,
                List.of(new TxIn(OutPoint.coinbase(), HexUtils.decode(
                        "04ffff001d010445"
                        + "5468652054696d65732030332f4a616e2f32303039204368616e63656c6c6f72206f6e"
                        + "206272696e6b206f66207365636f6e64206261696c6f757420666f722062616e6b73"),
                        TxIn.FINAL_SEQUENCE)),
                List.of(new TxOut(5_000_000_000L, HexUtils.decode(
                        "4104678afdb0fe5548271967f1a67130b7105cd6a828e03909a67962e0ea1f61deb649"
                        + "f6bc3f4cef38c4f35504e51ec112de5c384df7ba0b8d578a4c702b6bf11d5fac"))),
                new UInt32(0));
        long time;
        long nonce;
        long bits;
        switch (parameters.network()) {
            case MAINNET -> { time = 1231006505L; nonce = 2083236893L; bits = 0x1d00ffffL; }
            // TESTNET currently denotes testnet3, not testnet4.
            case TESTNET -> { time = 1296688602L; nonce = 414098458L; bits = 0x1d00ffffL; }
            case SIGNET -> { time = 1598918400L; nonce = 52613770L; bits = 0x1e0377aeL; }
            case REGTEST -> { time = 1296688602L; nonce = 2L; bits = 0x207fffffL; }
            default -> throw new IllegalArgumentException("Unsupported network");
        }
        Block block = new Block(new BlockHeader(1, new Hash256(new byte[32]), coinbase.txId(),
                new UInt32(time), new UInt32(bits), new UInt32(nonce)), List.of(coinbase));
        if (!block.hash().equals(parameters.genesisBlockHash())) {
            throw new IllegalArgumentException("Genesis hash does not match network parameters");
        }
        return block;
    }
}
