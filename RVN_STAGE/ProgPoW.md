# Ravencoin ProgPoW arithmetic and lane execution patch

Source audited: `node_bitco-main - 2026-10-23T231649.753.zip`.

## Scope

Eight new Java 21 production classes and three JUnit 5 test classes, all under `rvn-pow`:

- `RvnProgpowMath`: eight unsigned 32-bit arithmetic operators.
- `RvnProgpowRandom`: KISS99 PRNG.
- `RvnProgpowPermutation`: deterministic Fisher-Yates register shuffle.
- `RvnProgpowLaneState`: defensive 16 x 32 register state, arithmetic and merge.
- `RvnProgpowDagAccess`: checked DAG item/word access.
- `RvnProgpowOperation`: validated executable operation.
- `RvnProgpowProgram`: bounded sequence executor.
- `RvnProgpowProgramGenerator`: **test-only generator** (NOT consensus).

## Critical limitations

This patch is NOT a complete Ravencoin KawPoW implementation. In particular,
`RvnProgpowProgramGenerator` deliberately does not claim to match the actual
KawPoW height-dependent program generator, scheduling, DAG access patterns,
header nonce initialization, or final digest. Do NOT connect it to consensus
verification or mining until validated against the Ravencoin Core reference.

## Installation and tests

Unzip at repository root, then run:

    mvn -pl rvn-pow -am test
    mvn -pl rvn-app -am test

The new production classes and their direct dependencies were compiled with
JDK 21 using `javac`; Maven was not available in the authoring environment.
The JUnit tests have not been executed here.
