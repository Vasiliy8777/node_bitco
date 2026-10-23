# RVN stages 32–35: getheaders decode and validated-chain response

Source: `node_bitco-main - 2026-10-23T214350.033.zip`.

32. Strict bounded `getheaders` decoder: 1–101 locator hashes, exact 32-byte stop,
    no trailing bytes, canonical CompactSize delegated to existing parser.
33. Defensive copies for decoded request hashes and stop hash.
34. `RvnVerifiedHeadersResponder`: serves at most 2000 verified raw headers from
    active best-work chain, respects first matching locator and stop hash,
    never serves stale fork headers, returns empty if no locator matches.
35. JUnit regression tests for codec bounds, round-trip, defensive copies,
    fork exclusion, unknown locator, stop and fallback anchor.

**This does not implement or enable RVN consensus PoW.** Header data can only
be called verified if the underlying index was populated by a genuine verifier.
No P2P listener is automatically connected to the responder. The test fixture's
permissive verifier is TEST ONLY. No Bitcoin modules changed.

Run `mvn -pl rvn-app -am test` without clean.
