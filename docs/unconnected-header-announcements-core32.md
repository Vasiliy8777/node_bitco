# Unconnected header announcements — Bitcoin Core 32.1

Reference:
[CheckHeadersPoW, HandleUnconnectingHeaders and CMPCTBLOCK processing](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

An asynchronous HEADERS announcement whose parent is missing now requests
deeper headers from the current best-header locator. Previously the missing
parent escaped as an internal runtime error, without a recovery request.

The entire HEADERS batch must have valid claimed PoW and be continuous before
this recovery path runs. In particular, invalid PoW later in a batch must not be
hidden by its first missing parent. Contextual difficulty and timestamp checks
remain deferred until the ancestry is available.

The unknown batch is not persisted, acknowledged as a successful response, or
credited as a useful new block. Even a full 2000-header unknown batch requests
recovery rather than pagination from its unvalidated last header. The recovery
request uses the existing per-peer GETHEADERS gate: an outstanding recent request
is not cleared by an unconnected announcement, and normal producers share the
same strict two-minute retry limit.

Its last hash remains an untrusted availability announcement. Once the headers
arrive from another peer, the existing availability lookup can resolve that hash
against validated work and exclude failed ancestry. No premature work credit is
granted by remembering a hash.

Compact blocks follow Core's separate missing-parent branch: request deeper
headers only outside IBD, then return before header validation/reconstruction.
This branch does not update unknown-block availability. Even invalid claimed
PoW in this particular compact-block case is deferred, as in Core; HEADERS do
not get that exception. Existing connected-header rejection remains unchanged.

Focused verification passed all 59 cases, including real RocksDB header storage,
real regtest PoW, a full unconnected batch, later availability resolution,
invalid first/last PoW, and compact-block IBD/non-IBD behavior. Existing real
socket GETHEADERS admission tests also passed. Log:
`target/core-update-audit/core32-unconnected-headers-focused.log`.

The full Bitcoin/RVN reactor passed 5,126 cases: 5,114 executed, 12 opt-in skips,
zero failures/errors. Both applications packaged successfully, including the
native Bitcoin Core block acceptance matrix. Log:
`target/core-update-audit/core32-unconnected-headers-full-build.log`.

Production verification on November 3 at approximately 15:58 Asia/Bangkok:
exactly one Bitcoin process, PID 95780, original IDEA `BitcoinNodeApplication GPU`
configuration using `app/target/classes`. State `RUNNING`; authenticated RPC
height/headers 974373, IBD false, eight connections including two block-relay
peers. Two saved anchors loaded. CUDA logged accepted shares at approximately
2.49 GH/s. Evidence:
`target/core-update-audit/core32-unconnected-headers-live-verification.json`.

Scope: asynchronous relay handling. Synchronous pending-response failover policy,
full low-work presynchronization and complete compact-block negotiation/permission
parity remain separate work. This does not establish complete Core equivalence
or a full historical replay.
