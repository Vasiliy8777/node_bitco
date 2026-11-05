# Explicitly invalid headers — Bitcoin Core 32.1

Reference: [AcceptBlockHeader and ProcessNewBlockHeaders](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp).

Network header batches now reject an already stored header with an explicit
invalid-block marker (`duplicate-invalid`), and a new header whose immediate
parent carries that marker (`bad-prevblk`). Neither case can produce a successful
batch receipt, new-header credit or asynchronous continuation request. The
[subsequent preflight update](core32-seven-first-batch.md) releases GETHEADERS
admission before contextual rejection when PoW/continuity and connection checks
pass; admission acknowledgement is distinct from accepting the header batch.

The distinction between explicit failure and inherited failure matters: Core
32.1 returns success for an already known descendant that has no explicit
failure marker of its own. This implementation preserves that behavior while
continuing to exclude failed ancestry from best-header selection and downloads.
It does not replace the explicit flag test with an ancestor-wide rejection.

The existing atomic Java batch contract is retained: a failing batch does not
persist a valid prefix. Core inserts headers individually, so prefix persistence
is an intentional storage difference. Per-header progress callbacks can already
have run before a later header fails; successful-response metadata is withheld.

Tests use real RocksDB indexes and failure markers to check duplicate rejection,
new-child rejection, uncommitted prefixes, known-descendant acceptance and
acceptance after clearing the marker. An asynchronous relay test verifies that
a cached-invalid header cannot acknowledge a response or request continuation.

The exception carries a separate cached-invalid reason. The asynchronous HEADERS
handler ignores that rejection from an inbound peer without disconnecting or
granting success credit; outbound peers retain the invalid-header disconnect
path. Both directions are tested. The subsequent
[future-header update](future-header-rejection-core32.md) also implements the
cached-invalid exemption through compact-block header processing. Core's broader
permission exemptions are not established by these changes; see
[MaybePunishNodeForBlock](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

The full Bitcoin/RVN reactor passed 5,109 cases (5,097 executed, 12 opt-in skips,
zero failures/errors), including the native Core block-acceptance matrix:
`target/core-update-audit/core32-cached-invalid-full-build.log`.
The subsequent focused rebuild passed all 56 cases and checks the final inbound/outbound distinction:
`target/core-update-audit/core32-cached-invalid-final.log`.

Production verification on November 3 at approximately 14:53 Asia/Bangkok:
one Bitcoin process, PID 84308, original IDEA `BitcoinNodeApplication GPU`
configuration. State `RUNNING`; authenticated RPC height/headers 974369,
IBD false, nine connections including two block-relay peers. CUDA logged
accepted shares at approximately 2.64 GH/s. Evidence:
`target/core-update-audit/core32-cached-invalid-live-verification.json`.

This check does not establish full historical replay or complete Core parity.
