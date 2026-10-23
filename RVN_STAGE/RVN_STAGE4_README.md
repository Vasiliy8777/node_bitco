# Ravencoin Stage 4 — getheaders foundation

This is an **incremental patch only**, intended to apply on top of Stage 3.

## Included
- Canonical, bounded CompactSize codec with JUnit tests.
- `getheaders` payload builder with strict locator count and 32-byte wire-order hashes.
- `RvnPeerSession.requestHeaders(...)` for sending a request over an established session.

**Not implemented:** header decoding, header chain validation, KawPoW, chainwork, persistent storage, IBD or mining. Never treat received `headers` as validated.

## Install
Unzip into the project root, retaining directories. Overwrite only the RVN peer session file.

## Test
`mvn -pl rvn-app -am test`

No `clean`: avoid deleting root `target` used by the Bitcoin GC logging configuration.

## Notes
The supplied archive of the project predates Stage 3; Stage 4 assumes that the Stage 3 patch has already been applied (as confirmed in the conversation). No Bitcoin source, Bitcoin configuration, or root pom.xml is changed.
