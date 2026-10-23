# Ravencoin Stage 3 — bounded outbound peer session

This is a **delta patch** against the user's 2026-10-23 19:29 project archive.
Extract at the repository root, preserving directory names and replacing only the listed files.
No Bitcoin source, settings, run configuration, database, or mining logic is included or changed.

## Changes
- Fix duplicate `rvn-p2p` dependency in `../rvn-app/pom.xml`.
- Add `RvnPeerSession` (persistent outbound TCP connection, handshake, ping/pong, bounded incoming queue, timeouts, close, error reporting).
- Add loopback JUnit tests for the session.
- Add optional short session smoke test in `rvn-app`; disabled by default.

## Tests (without Maven clean)
`mvn -pl rvn-app -am test`
`mvn -pl rvn-app -am package -DskipTests`

## Optional session test against your own RVN Core peer
`java -jar rvn-app/target/rvn-app-0.0.1-SNAPSHOT.jar --ravencoin.network=regtest --ravencoin.p2p.session-enabled=true --ravencoin.p2p.host=127.0.0.1 --ravencoin.p2p.port=18444 --ravencoin.p2p.session-duration-ms=5000`

Use the port of your *Ravencoin Core* instance. Do not confuse Bitcoin Core regtest port with RVN; both may default to 18444 and cannot share it simultaneously.

## Limits
No peer discovery, headers download, block validation, UTXO, KawPoW, or mining. This is a foundation only.
