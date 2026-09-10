package ru.bitcoin.node.chain;

import ru.bitcoin.node.consensus.pow.ChainWork;
import ru.bitcoin.node.protocol.block.BlockHeader;

import java.math.BigInteger;

public final class BlockIndexFactory {

    private BlockIndexFactory() {
    }

    public static BlockIndex createGenesis(
            BlockHeader header
    ) {
        if (header == null) {
            throw new IllegalArgumentException(
                    "header must not be null"
            );
        }

        BigInteger blockWork =
                ChainWork.blockWork(
                        header.bits().value()
                );

        return new BlockIndex(
                header.hash(),
                header,
                0,
                header.previousBlockHash(),
                blockWork
        );
    }

    public static BlockIndex createChild(
            BlockIndex parent,
            BlockHeader header
    ) {
        if (parent == null) {
            throw new IllegalArgumentException(
                    "parent must not be null"
            );
        }

        if (header == null) {
            throw new IllegalArgumentException(
                    "header must not be null"
            );
        }

        if (!header.previousBlockHash().equals(
                parent.hash()
        )) {
            throw new IllegalArgumentException(
                    "header does not reference parent block"
            );
        }

        BigInteger blockWork =
                ChainWork.blockWork(
                        header.bits().value()
                );

        BigInteger chainWork =
                ChainWork.add(
                        parent.chainWork(),
                        blockWork
                );

        return new BlockIndex(
                header.hash(),
                header,
                parent.height() + 1,
                header.previousBlockHash(),
                chainWork
        );
    }
}