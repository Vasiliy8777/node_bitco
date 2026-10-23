# RVN Stage 5 — header framing and bounded getheaders request

Only new files are included. No existing Bitcoin or Ravencoin sources are overwritten.

Implemented:
- RVN 80-byte pre-KawPoW and 120-byte KawPoW header decoding, selected by nTime.
- Mainnet activation timestamp 1588788000 from Ravencoin Core chainparams.cpp.
- KawPoW 80-byte input digest SHA256d (NOT final PoW hash or block ID).
- Strict CompactSize-based `headers` message decoding/encoding, max 2000 entries, mandatory zero tx count, no trailing bytes.
- Structural batch checks (nonzero nBits, consecutive declared KawPoW heights).
- One bounded `getheaders` request-response round-trip over an existing `RvnPeerSession`.
- JUnit 5 tests for formats, boundaries, malformed data, defensive copies.

NOT implemented: X16R/X16RV2/KawPoW verification, block IDs, difficulty adjustment,
chainwork, prev-hash linking, persistent validated header index, continuous IBD or GPU mining.
In particular: never persist received headers as validated or use them as trusted locators.

Run from project root without `clean`:
    mvn -pl rvn-app -am test
    mvn -pl rvn-app -am package -DskipTests

The API can be invoked by a future header sync coordinator; it is not automatically
enabled on startup. Testnet/regtest activation times must be verified before use.
Official references:
https://github.com/RavenProject/Ravencoin/blob/master/src/primitives/block.h
https://github.com/RavenProject/Ravencoin/blob/master/src/chainparams.cpp
