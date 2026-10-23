# RVN Stage 6 — bounded peer scheduling + crash-recoverable UNVERIFIED header spool

Patch contains ONLY new files; no Bitcoin files, no root pom.xml, no changes to existing RVN APIs.

## Added
- RvnPeerEndpoint — validated immutable host/port.
- RvnPeerPool — thread-safe, bounded, deduplicated candidate registry.
- RvnPeerBackoff — bounded exponential reconnect delay using monotonic clock inputs.
- RvnHeadersBatch — immutable decoded `headers` payload with structural checks and payload digest.
- RvnHex — lowercase hex for content-addressed file naming.
- RvnUnverifiedHeaderSpool — on-disk untrusted batch archive:
  - atomic temporary-file rename when supported;
  - CRC32 and SHA256d(payload) integrity checks;
  - strict payload/file-count limits and path validation;
  - restart replay via list/load, duplicate detection and explicit deletion.
- Three JUnit test classes covering backoff, peer registry, spool corruption/restart and payload safety.

## Critical consensus boundary
The spool stores UNVERIFIED messages. SHA256d(payload) is only a content ID.
It is NOT a Ravencoin block hash, NOT chainwork, and must NEVER be used
as a block locator, validated chain tip or mining input.

This stage does not yet implement continuous IBD, canonical header hashes,
KawPoW/X16R/X16RV2 PoW verification, difficulty adjustment or UTXO state.
Do not assume the 120-byte wire format alone proves consensus validity.

No new behavior is enabled in the application. This is an independently testable
infrastructure layer for the later validated-header index and sync coordinator.

## Commands (no clean)
mvn -pl rvn-app -am test
mvn -pl rvn-app -am package -DskipTests

Note: Maven and JUnit execution unavailable in the assistant environment.
All RVN main Java sources were compiled with javac --release 21.
