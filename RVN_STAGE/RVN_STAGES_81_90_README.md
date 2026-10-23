# RVN Stages 81–90: real Keccak-256 and KawPoW low-level arithmetic

Based on: `node_bitco-main - 2026-10-23T224144.629.zip`.

Changes:
- Implemented legacy Keccak-256 (not NIST SHA3-256) with a shared Keccak-f[1600] sponge.
- Refactored existing Keccak-512 to use the shared verified permutation.
- Implemented 32-bit FNV-1a mix and reduction for KawPoW/ProgPoW.
- Implemented explicit 32-bit little-endian word serialization for KawPoW.
- Added tests for Keccak-256 empty/abc known answers, Keccak-512 regression,
  SHA3 mismatch, padding boundaries, unsigned overflow and word ordering.

IMPORTANT: These are genuine cryptographic/arithmetic building blocks, NOT
full X16R, X16RV2 or KawPoW. Missing algorithms, DAG generation, difficulty
and official Ravencoin block vectors remain unimplemented. No production
consensus activation is included. This patch does not change Bitcoin modules.

Run: `mvn -pl rvn-pow -am test` and `mvn -pl rvn-app -am test`.
