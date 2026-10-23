# Ravencoin integration — Stage 1 (NOT a functioning miner)

Source base: supplied node_bitco-main archive dated 2026-10-16. Original Bitcoin classes are unchanged.

Added modules: `rvn-consensus` (strict uint32 nBits/256-bit target primitive + tests) and `rvn-app` (independent Spring Boot launcher). Root `../pom.xml` gains these modules. `rvn-app` does not depend on `app` and cannot accidentally start Bitcoin P2P/mining. Its mining flag deliberately refuses to start.

## Build / test

With Maven 3.9+ and JDK 21 toolchain configured:

```powershell
mvn -pl rvn-consensus,rvn-app -am test
mvn -pl rvn-app -am package -DskipTests
java -jar rvn-app/target/rvn-app-0.0.1-SNAPSHOT.jar
```

Expected: message `stage 1: isolated startup OK; P2P/chain/KawPoW mining NOT implemented.`

## Next stages (must be validated against Ravencoin Core)

1. Official chain parameters, block header serialization, version/network rules, genesis and independent vectors.
2. Full KawPoW/ProgPoW verification and official consensus tests.
3. P2P headers/blocks, persistent chainstate, UTXO, reorg, mempool.
4. Block template and coinbase payout, CUDA KawPoW GPU worker and candidate submission.
5. Isolated test network and independently verified block acceptance, then mainnet.

**Do not set `ravencoin.mining.enabled=true` expecting real mining.** The guard fails intentionally.
