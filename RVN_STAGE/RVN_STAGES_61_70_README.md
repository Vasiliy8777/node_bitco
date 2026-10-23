# RVN stages 61–70: PoW safety, X16RV2 pipeline, vector harness, JNI mix contract

Source: `node_bitco-main - 2026-10-23T222628.893.zip`.

## Changes
1. Strict KawPoW router: generic RvnPowEngine can no longer bypass mix validation.
2. Explicit per-algorithm multi-engine, preventing cross-algorithm fallback.
3. X16RV2 16-round execution with Tiger192 substitution contracts.
4. Validate Tiger192 (24 bytes) and each 512-bit round (64 bytes).
5. Defensive copies at all primitive boundaries.
6. Strict hex codec for reference vectors.
7. TSV vector corpus reader with duplicate detection.
8. Hardened self-test requiring coverage of all three algorithms.
9. Separate KawPoW JNI ABI returning final ID, PoW and computed mix (96 bytes).
10. Regression tests for round selection, malformed vectors and KawPoW rejection.

## Security / completion status
This patch DOES NOT contain actual implementations of the 16 X16R hash
primitives, Tiger192, or KawPoW DAG/ProgPoW computation. The JNI ABI is
only a binding and requires a separately compiled verified library.
No official Ravencoin Core vectors are included or claimed to have passed.
Do not enable the PoW engine for production validation without authoritative
vectors and an audited implementation.

## Install and run
Extract into project root, overwriting files.
`mvn -pl rvn-pow -am test`
`mvn -pl rvn-app -am test`
