package ru.bitcoin.node.protocol.network;

import ru.bitcoin.node.common.types.Hash256;

import java.math.BigInteger;
import java.util.Objects;

public final class NetworkParameters {
    private final long segwitHeight;
    private final long bip66Height;
    private final long bip65Height;
    private final long csvHeight;
    private final long bip34Height;
    private final long subsidyHalvingInterval;
    private final BitcoinNetwork network;
    private final long magic;
    private final int defaultPort;
    private final Hash256 bip16ExceptionBlockHash;
    private final Hash256 genesisBlockHash;

    private final BigInteger powLimit;

    private final long targetSpacingSeconds;
    private final long targetTimespanSeconds;

    private final boolean allowMinDifficultyBlocks;
    private final boolean enforceBip94;
    private final boolean noRetargeting;
    private final BigInteger minimumChainWork;
    private final Hash256 defaultAssumeValid;

    public NetworkParameters(
            BitcoinNetwork network,
            long magic,
            int defaultPort,
            Hash256 genesisBlockHash,
            Hash256 bip16ExceptionBlockHash,
            BigInteger powLimit,
            long targetSpacingSeconds,
            long targetTimespanSeconds,
            long subsidyHalvingInterval,
            long bip34Height,
            long bip66Height,
            long bip65Height,
            long csvHeight,
            long segwitHeight,
            boolean allowMinDifficultyBlocks,
            boolean enforceBip94,
            boolean noRetargeting
    ) {
        this(network, magic, defaultPort, genesisBlockHash, bip16ExceptionBlockHash, powLimit,
                targetSpacingSeconds, targetTimespanSeconds, subsidyHalvingInterval, bip34Height,
                bip66Height, bip65Height, csvHeight, segwitHeight, allowMinDifficultyBlocks,
                enforceBip94, noRetargeting, BigInteger.ZERO, new Hash256(new byte[Hash256.LENGTH]));
    }

    public NetworkParameters(
            BitcoinNetwork network,
            long magic,
            int defaultPort,
            Hash256 genesisBlockHash,
            Hash256 bip16ExceptionBlockHash,
            BigInteger powLimit,
            long targetSpacingSeconds,
            long targetTimespanSeconds,
            long subsidyHalvingInterval,
            long bip34Height,
            long bip66Height,
            long bip65Height,
            long csvHeight,
            long segwitHeight,
            boolean allowMinDifficultyBlocks,
            boolean enforceBip94,
            boolean noRetargeting,
            BigInteger minimumChainWork,
            Hash256 defaultAssumeValid
    ) {
        if (segwitHeight < 0) {
            throw new IllegalArgumentException(
                    "segwitHeight must not be negative"
            );
        }

        if (bip66Height < 0) {
            throw new IllegalArgumentException(
                    "bip66Height must not be negative"
            );
        }

        if (bip65Height < 0) {
            throw new IllegalArgumentException(
                    "bip65Height must not be negative"
            );
        }

        if (network == null) {
            throw new IllegalArgumentException(
                    "network must not be null"
            );
        }
        if (csvHeight < 0) {
            throw new IllegalArgumentException(
                    "csvHeight must not be negative"
            );
        }
        if (bip34Height < 0) {
            throw new IllegalArgumentException(
                    "bip34Height must not be negative"
            );
        }
        if (subsidyHalvingInterval <= 0) {
            throw new IllegalArgumentException(
                    "subsidyHalvingInterval must be positive"
            );
        }

        if (genesisBlockHash == null) {
            throw new IllegalArgumentException(
                    "genesisBlockHash must not be null"
            );
        }

        if (powLimit == null || powLimit.signum() <= 0) {
            throw new IllegalArgumentException(
                    "powLimit must be positive"
            );
        }

        if (defaultPort <= 0 || defaultPort > 65535) {
            throw new IllegalArgumentException(
                    "Invalid default port"
            );
        }

        if (targetSpacingSeconds <= 0) {
            throw new IllegalArgumentException(
                    "targetSpacingSeconds must be positive"
            );
        }

        if (targetTimespanSeconds <= 0) {
            throw new IllegalArgumentException(
                    "targetTimespanSeconds must be positive"
            );
        }

        this.network = network;
        this.magic = magic;
        this.defaultPort = defaultPort;
        this.genesisBlockHash = genesisBlockHash;
        this.powLimit = powLimit;
        this.targetSpacingSeconds = targetSpacingSeconds;
        this.targetTimespanSeconds = targetTimespanSeconds;
        this.allowMinDifficultyBlocks = allowMinDifficultyBlocks;
        this.enforceBip94 = enforceBip94;
        this.noRetargeting = noRetargeting;
        if (minimumChainWork == null || minimumChainWork.signum() < 0) {
            throw new IllegalArgumentException("minimumChainWork must not be negative");
        }
        this.minimumChainWork = minimumChainWork;
        this.defaultAssumeValid = Objects.requireNonNull(defaultAssumeValid, "defaultAssumeValid");
        this.subsidyHalvingInterval = subsidyHalvingInterval;
        this.bip34Height = bip34Height;
        this.bip66Height = bip66Height;
        this.bip65Height = bip65Height;
        this.csvHeight = csvHeight;
        this.bip16ExceptionBlockHash = bip16ExceptionBlockHash;
        this.segwitHeight = segwitHeight;
    }

    public BitcoinNetwork network() {
        return network;
    }

    public long magic() {
        return magic;
    }

    public int defaultPort() {
        return defaultPort;
    }

    public Hash256 genesisBlockHash() {
        return genesisBlockHash;
    }

    public BigInteger powLimit() {
        return powLimit;
    }

    public long targetSpacingSeconds() {
        return targetSpacingSeconds;
    }

    public long targetTimespanSeconds() {
        return targetTimespanSeconds;
    }

    public int difficultyAdjustmentInterval() {
        return Math.toIntExact(
                targetTimespanSeconds / targetSpacingSeconds
        );
    }

    public boolean allowMinDifficultyBlocks() {
        return allowMinDifficultyBlocks;
    }

    public boolean enforceBip94() {
        return enforceBip94;
    }

    public boolean noRetargeting() {
        return noRetargeting;
    }

    public BigInteger minimumChainWork() {
        return minimumChainWork;
    }

    public Hash256 defaultAssumeValid() {
        return defaultAssumeValid;
    }

    /** Bitcoin Core chain parameter: do not prune before the active chain reaches this height. */
    public long pruneAfterHeight() {
        return switch (network) {
            case MAINNET -> 100_000L;
            case TESTNET, TESTNET4, SIGNET, REGTEST -> 1_000L;
        };
    }

    public long subsidyHalvingInterval() {
        return subsidyHalvingInterval;
    }
    public long bip34Height() {
        return bip34Height;
    }
    public long csvHeight() {
        return csvHeight;
    }
    public long bip66Height() {
        return bip66Height;
    }

    public long bip65Height() {
        return bip65Height;
    }

    public Hash256 bip16ExceptionBlockHash() {
        return bip16ExceptionBlockHash;
    }
    public long segwitHeight() {
        return segwitHeight;
    }
    /** Trusted AssumeUTXO snapshot commitment copied from Bitcoin Core chain parameters. */
    public record AssumeUtxoData(long height, Hash256 hashSerialized, long chainTxCount, Hash256 blockHash) {
        public AssumeUtxoData {
            if (height < 0) throw new IllegalArgumentException("height must not be negative");
            Objects.requireNonNull(hashSerialized, "hashSerialized");
            if (chainTxCount < 0) throw new IllegalArgumentException("chainTxCount must not be negative");
            Objects.requireNonNull(blockHash, "blockHash");
        }
    }

    /** Security-critical AssumeUTXO anchors. Keep synchronized with the supported Bitcoin Core baseline. */
    public java.util.List<AssumeUtxoData> assumeUtxoData() {
        return switch (network) {
            case MAINNET -> java.util.List.of(
                    assume(840_000L, "a2a5521b1b5ab65f67818e5e8eccabb7171a517f9e2382208f77687310768f96", 991_032_194L, "0000000000000000000320283a032748cef8227873ff4872689bf23f1cda83a5"),
                    assume(880_000L, "dbd190983eaf433ef7c15f78a278ae42c00ef52e0fd2a54953782175fbadcea9", 1_145_604_538L, "000000000000000000010b17283c3c400507969a9c2afd1dcf2082ec5cca2880"),
                    assume(910_000L, "4daf8a17b4902498c5787966a2b51c613acdab5df5db73f196fa59a4da2f1568", 1_226_586_151L, "0000000000000000000108970acb9522ffd516eae17acddcb1bd16469194a821"),
                    assume(935_000L, "e4b90ef9eae834f56c4b64d2d50143cee10ad87994c614d7d04125e2a6025050", 1_305_397_408L, "0000000000000000000147034958af1652b2b91bba607beacc5e72a56f0fb5ee"),
                    assume(965_000L, "4a8d794337118c0c615b574f817c7306c687584a537184b8d233df42bf477ec2", 1_429_611_231L, "00000000000000000001595977e6000ce56129f5c9b4073e31ccc30b90b97da9")
            );
            case TESTNET -> java.util.List.of(
                    assume(2_500_000L, "f841584909f68e47897952345234e37fcd9128cd818f41ee6c3ca68db8071be7", 66_484_552L, "0000000000000093bcb68c03a9a168ae252572d348a2eaeba2cdf9231d73206f"),
                    assume(4_840_000L, "ce6bb677bb2ee9789c4a1c9d73e6683c53fc20e8fdbedbdaaf468982a0c8db2a", 536_078_574L, "00000000000000f4971a7fb37fbdff89315b69a2e1920c467654a382f0d64786"),
                    assume(5_125_000L, "d05430f34c9b7dd7eb98c0718cdf03782bcce8273847557d68ac2efc1365d4b8", 536_708_663L, "00000000000009ad1946e21cb4f1a6323ee99c89017b59d5166472672b868133")
            );
            case TESTNET4 -> java.util.List.of(
                    assume(90_000L, "784fb5e98241de66fdd429f4392155c9e7db5c017148e66e8fdbc95746f8b9b5", 11_347_043L, "0000000002ebe8bcda020e0dd6ccfbdfac531d2f6a81457191b99fc2df2dbe3b")
            );
            case SIGNET -> java.util.List.of(
                    assume(160_000L, "fe0a44309b74d6b5883d246cb419c6221bcccf0b308c9b59b7d70783dbdf928a", 2_289_496L, "0000003ca3c99aff040f2563c2ad8f8ec88bd0fd6b8f0895cfaf1ef90353a62c"),
                    assume(290_000L, "97267e000b4b876800167e71b9123f1529d13b14308abec2888bbd2160d14545", 28_547_497L, "0000000577f2741bb30cd9d39d6d71b023afbeb9764f6260786a97969d5c9ac0"),
                    assume(320_000L, "1aaf72ecb376cc16957fbb8d5d406bfd6e3165510e2fc83879b6d14cd20b4462", 32_079_110L, "0000000740ae66b284da84387dcfa14d7b1385b0bad482005ba4e770ea6c4b95")
            );
            case REGTEST -> java.util.List.of(
                    assume(110L, "86e9a1205b418b16dde3a18a78c730e30137e28466bda5dbf6b33ab8fc05447c", 111L, "135eec25a6fb277884e5824e7aa7d052c4868161c99a5122170b5266f86c273d"),
                    assume(200L, "17dcc016d188d16068907cdeb38b75691a118d43053b8cd6a25969419381d13a", 201L, "385901ccbd69dff6bbd00065d01fb8a9e464dede7cfe0372443884f9b1dcf6b9"),
                    assume(299L, "106b2c56233e378a824cf0d5ff2be42ed32c72f1605c9be288d00942908a40ac", 334L, "0c552ced4721c249a389eb9b08cb8da261cd46f0e7b5f9d064d48f3113406853")
            );
        };
    }

    public java.util.Optional<AssumeUtxoData> assumeUtxoForBlock(Hash256 blockHash) {
        Objects.requireNonNull(blockHash, "blockHash");
        return assumeUtxoData().stream().filter(data -> data.blockHash().equals(blockHash)).findFirst();
    }

    private static AssumeUtxoData assume(long height, String hashSerialized, long chainTxCount, String blockHash) {
        return new AssumeUtxoData(height, Hash256.fromDisplayHex(hashSerialized), chainTxCount, Hash256.fromDisplayHex(blockHash));
    }

}

