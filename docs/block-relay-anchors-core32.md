# Block-relay restart anchors

Implemented 2026-11-02 against the restart policy in
[Core v32.1 net.cpp](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net.cpp).

During clean supervisor shutdown, up to two READY block-relay-only endpoints are
saved with their negotiated service flags. Full-relay peers, inbound peers,
unfinished handshakes and closed peers are excluded. Saving failure is logged
without interrupting shutdown of the node's databases.

Once the initial header connection has started the outbound supervisor, these
addresses are consumed and tried preferentially for available block-relay slots.
Ordinary full-relay workers cannot reserve a pending anchor's endpoint. Occupied
endpoints/network groups, banned/discouraged/local/unsupported addresses and
unsuitable services are excluded. NODE_NETWORK_LIMITED is eligible only outside
IBD; the normal post-handshake service/height validation still applies. A failed
preferential attempt falls back to normal AddrMan selection and backoff.

Reading consumes the anchor file even when it is corrupt. This matches Core's
one-shot lifecycle: a crash after startup cannot repeatedly reuse the old file.
Offline or never-started supervisors preserve existing anchors. Peers are saved
only during a clean shutdown, not periodically.

The file is `block-relay-anchors.dat` under this application's network data
directory. It uses an application-specific versioned binary format, network magic
and a double-SHA256 checksum; it does not read or overwrite Core's `anchors.dat`.
Reads are capped at 1024 bytes, count at two, and address lengths at the BIP155
network's fixed size. Writes use a forced temporary file and atomic replacement
where supported. Database and chainstate formats are unchanged.

Regression coverage includes persistence/consumption, count and service-bit
preservation, wrong network/checksum/size, IBD/service filtering, bans and local/
documentation addresses. Controlled public endpoints map to an isolated local
test server: real handshakes verify preferential selection, fallback, concurrent
full-relay reservations and persistence of block-relay peers only.

The full Bitcoin/RVN build completed 5016 cases: 5004 executed successfully,
12 opt-in cases skipped, zero failures/errors (`core32-anchors-full-build.log`).
The subsequent focused run passed eight anchor cases, including the additional
concurrent full-relay reservation scenario (`core32-anchors-final-focused.log`).

For live verification, the updated mainnet process (PID 53100) established two
block-relay connections, then stopped cleanly. Its log recorded two saved
anchors. The actual 82-byte file under `K:/BitcoinJavaNode/mainnet` contained
two records and a valid double-SHA256 checksum. The next startup was launched
as PID 71472. It logged loading two anchors and attempting a saved endpoint;
the consumed file was absent after startup. One previous anchor was confirmed
as a block-relay connection again. Authenticated RPC reported height and headers
974159, IBD false, eight connections including two block-relay peers. CUDA
reported approximately 2.59 GH/s and 26 accepted shares after restart.
Raw observations are stored in `target/core-update-audit/`, including
`core32-anchors-live-verification.json` and `core32-anchors-reuse-live.json`.

This supplies restart anchoring. The subsequent
[stale-tip recovery phase](stale-tip-recovery-core32.md) adds extra full-relay
probing and eviction; the subsequent [rotation phase](outbound-rotation-core32.md)
adds periodic extra block-relay connections and chainwork catch-up eviction.
The initial header
bootstrap connection still precedes anchor attempts in this application's
startup architecture; an anchor already represented by that connection or its
network group is skipped.
