# Seven-workstream implementation: first batch

Reference: Bitcoin Core v32.1
[network processing](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp),
[blockchain RPC](https://github.com/bitcoin/bitcoin/blob/v32.1/src/rpc/blockchain.cpp),
[background progress](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp).

GETHEADERS admission is now released after the complete response passes claimed
PoW and continuity checks and its first parent is known, before contextual or
cached-invalid acceptance checks. This callback is shared by asynchronous relay
and the synchronous coordinator. Missing ancestry and invalid claimed PoW retain
the outstanding request. Durable receipts and useful-block credit still require
successful contextual processing. This supersedes the later-release limitation
in earlier admission/reception reports.

CMPCTBLOCK is ignored before decoding until SENDCMPCT negotiated supported
version 2. Malformed data from a negotiated peer still follows normal rejection.
GETBLOCKTXN can use a bounded one-block in-memory cache before disk/depth serving
policy; index bounds and empty-request rejection remain enforced. The cache is
updated with connected blocks and replaced on the next connected-block callback.
This does not yet reproduce Core's optimistic PoW-valid side-block caching.

The optional `getblockchaininfo.backgroundvalidation` object now contains
`snapshotheight`, `blocks`, `bestblockhash`, `mediantime`, `chainwork` and
`verificationprogress`. Progress uses committed cumulative transaction count
divided by the trusted snapshot's transaction count. Missing historical counts
produce 0, as Core does, rather than an invented height-based approximation.
The progress store uses a backward-readable version 2 format and commits counts
with each validated background block. Completed background verification omits
the object. Ordinary-node status RPC retains its nonblocking path when no
snapshot is active. `getchainstates.snapshot_blockhash` identifies the snapshot
base rather than the moving active tip.

Tests cover both header response routes, contextual rejection and retries,
missing-parent retention, negotiated/unsupported/missing compact versions,
cached old-block transactions and cache replacement, durable transaction counts
and legacy-state reads, background report fields and completion, and RPC
availability while block validation holds the chain lock. Workstream status is
tracked in [the seven-workstream checklist](core32-seven-workstreams.md).

These changes do not close all seven workstreams or establish full Core parity.

The full Bitcoin/RVN reactor passed 5,133 cases: 5,121 executed, 12 opt-in skips,
zero failures/errors. Both applications packaged successfully, including the
native Core acceptance matrix. Log:
`target/core-update-audit/core32-seven-first-full-build.log`.

Live verification on November 3 at approximately 17:32 Asia/Bangkok: original
IDEA GPU configuration, exactly one Bitcoin process (PID 94220), state RUNNING,
height/headers 974381, IBD false, four connections including two block-relay
peers. Two saved anchors loaded and CUDA resumed with accepted shares. Evidence:
`target/core-update-audit/core32-seven-first-live-verification.json`.
