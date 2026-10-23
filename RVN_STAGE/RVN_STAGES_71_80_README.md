# RVN PoW: stages 71–80 — first real cryptographic primitives

Input: `node_bitco-main - 2026-10-23T223458.028.zip`.

This patch adds **actual implementations** of two X16R round primitives:
* Keccak-512 with original Keccak suffix `0x01`, 72-byte rate and 24 Keccak-f[1600] rounds, implemented in Java.
* SHA-512 through Java 21 `MessageDigest("SHA-512")`.

Also added:
* Explicit algorithm-ID primitive registry, with missing implementations rejected.
* JUnit known-answer tests for Keccak-512 (empty, abc), negative SHA3-512 comparison,
  SHA-512 JDK cross-check, registry fail-closed tests and defensive copies.
* Manual cross-check: 11 Keccak-512 inputs (including 71/72/73/143/144/145 bytes)
  matched `openssl dgst -keccak-512` byte-for-byte.

## NOT complete
Remaining X16R primitives: BLAKE, BMW, GROESTL, JH, SKEIN, LUFFA,
CUBEHASH, SHAVITE, SIMD, ECHO, HAMSI, FUGUE, SHABAL, WHIRLPOOL.
Tiger192, KawPoW DAG/mix, historical consensus difficulty and verified
Ravencoin Core block vectors are NOT implemented. Do not enable this
as a production consensus engine.

Extract into project root. Run:
`mvn -pl rvn-pow -am test`
`mvn -pl rvn-app -am test`
