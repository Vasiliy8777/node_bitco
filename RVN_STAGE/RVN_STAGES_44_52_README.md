# Large RVN PoW integration block (Stages 44–52)
Source archive: node_bitco-main - 2026-10-23T220707.897.zip.

## Delivered
- New independent `rvn-pow` Maven module and root reactor wiring.
- Three-algorithm selection by **trusted block height**.
- Strict 80/120-byte header validation, KawPoW declared-height validation.
- Immutable defensive-copy result and trusted test-vector format.
- Fail-closed PoW engine contract and unavailable default.
- JNI adapter with strict 64-byte result contract and explicit failure.
- Vector-based engine self-test requiring all 3 algorithms.
- Staged-index-aware consensus adapter (block ID + target) and factory
  integrating the existing difficulty/MTP checks.
- JUnit tests for schedule boundaries, header rules, defensive copies,
  fail-closed behavior, and vector self-test.
- Native ABI documentation.

## CRITICAL LIMITATION
**This is NOT a completed implementation of X16R, X16RV2 or KawPoW.**
Their actual cryptographic algorithms, validated native implementation,
historical consensus difficulty, activation constants, and Ravencoin Core
reference vectors are NOT included. A real consensus verifier cannot
be enabled from this patch alone. Do not configure a permissive
difficulty engine or test-only hash provider for production.

The purpose is to establish a strict, compilable integration boundary
without silently substituting invalid hashes for Ravencoin PoW.

## Commands
mvn -pl rvn-pow -am test
mvn -pl rvn-app -am test

No BTC Java classes modified. Only root pom is touched to add the module.
