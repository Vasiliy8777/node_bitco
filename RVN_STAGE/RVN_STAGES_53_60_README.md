# RVN stages 53–60 — X16R ordering/round chain and KawPoW mix safety

Source: `node_bitco-main - 2026-10-23T221830.014.zip`.

1. Exact 16-algorithm X16R ordering from previous hash **wire byte order**.
2. Names/indices of all sixteen primitives.
3. Reusable 16-round 512-bit chain (first input 80 bytes, later 64 bytes).
4. Fail-closed primitive interface; no fake JCA substitutes.
5. Separate KawPoW mix calculation contract.
6. Mandatory verification of independently computed mix against header bytes 88–119.
7. Updated router refusing a generic KawPoW engine without mix verification.
8. JUnit regression tests for order, round sequence, malformed primitive, mix mismatch.

**Critical:** This is not a complete consensus implementation. The 16 exact
cryptographic primitives are not implemented; X16RV2-specific changes, real
KawPoW DAG/ProgPoW calculation, reference vectors, difficulty retargeting,
activation parameters and a trusted JNI backend are also missing.
The test-only primitives are deliberately synthetic and MUST NOT be used in
production. All algorithms remain fail-closed unless backed by real verified
implementations. Existing Bitcoin modules untouched.

Run `mvn -pl rvn-pow -am test`.
