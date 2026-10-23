package ru.bitcoin.node.rvn.pow;

import java.util.HexFormat;
import ru.bitcoin.node.rvn.consensus.RvnHash256;
import ru.bitcoin.node.rvn.consensus.RvnMainnetExpectedBits;

/** Mainnet genesis plus Core-C-generated X16RV2 known answers for all Tiger substitutions. */
public final class RvnHistoricalPowSelfTest {
    private static final String[][] TIGER_VECTORS = {
        {"4c7196bb4444444444444444082d52779cc1e60b30557a9fc4e90e33587da2c7ec11365b80a5caef14395e83a8cdf2173c6186abd0f51a3f6489aed3f81d42678cb1d6fb20456a8fb4d9fe23486d92b7",
         "e3922fa5930a8d2fce3afee8177716ca11bccca1eceddb25d0500e78a80af9fc"},
        {"7297bce166666666666666662e53789dc2e70c31567ba0c5ea0f34597ea3c8ed12375c81a6cbf0153a5f84a9cef3183d6287acd1f61b40658aafd4f91e43688db2d7fc21466b90b5daff24496e93b8dd",
         "942413addbd5d33370d053eb8302b31d8e30e94df0540593cdfbbc4a912ae224"},
        {"1d42678cffffffffffffffffd9fe23486d92b7dc01264b7095badf04294e7398bde2072c51769bc0e50a2f54799ec3e80d32577ca1c6eb10355a7fa4c9ee13385d82a7ccf1163b6085aacff4193e6388",
         "108a08f48ffaecad9455147ab958a5fd34bb7b0a20ffe862336fc0296a8674d1"}
    };

    private RvnHistoricalPowSelfTest() {}

    public static byte[] mainnetGenesisHeader() {
        byte[] header = new byte[80];
        RvnKawpowWords.writeLE(4, header, 0);
        byte[] merkle = HexFormat.of().parseHex(
                "28ff00a867739a352523808d301f504bc4547699398d70faf2266a8bae5f3516");
        for (int i = 0; i < 32; i++) header[36 + i] = merkle[31 - i];
        RvnKawpowWords.writeLE(1514999494, header, 68);
        RvnKawpowWords.writeLE(0x1e00ffff, header, 72);
        RvnKawpowWords.writeLE(25023712, header, 76);
        return header;
    }

    public static void requireValid(RvnPowEngine engine) throws RvnPowException {
        var genesis = engine.calculate(RvnPowAlgorithm.X16R, mainnetGenesisHeader());
        if (!new RvnHash256(genesis.blockIdWire()).equals(RvnMainnetExpectedBits.GENESIS_ID)
                || !java.util.Arrays.equals(genesis.blockIdWire(), genesis.powDigestWire()))
            throw new RvnPowException("X16R mainnet genesis self-test failed");
        for (String[] vector : TIGER_VECTORS) {
            var actual = engine.calculate(RvnPowAlgorithm.X16RV2, HexFormat.of().parseHex(vector[0]));
            if (!HexFormat.of().formatHex(actual.blockIdWire()).equals(vector[1])
                    || !java.util.Arrays.equals(actual.blockIdWire(), actual.powDigestWire()))
                throw new RvnPowException("X16RV2 Tiger substitution self-test failed");
        }
    }
}
