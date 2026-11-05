# Manual-only bootstrap and PRESYNC RPC — Core 32.1

References: [manual connections outside automatic slots](https://github.com/bitcoin/bitcoin/blob/v32.1/src/rpc/net.cpp#L325-L334),
[RPC height semantics](https://github.com/bitcoin/bitcoin/blob/v32.1/src/rpc/net.cpp#L180-L183),
[height retained during redownload](https://github.com/bitcoin/bitcoin/blob/v32.1/src/headerssync.h#L120-L126).

With no automatic connection budget and explicit manual endpoints, lifecycle
startup now waits for a manual handshake, synchronizes headers and blocks,
enforces minimum chainwork, enters live sync and arms mining only after the
normal freshness/IBD checks. It does not start discovery or automatic outbound
supervision. With no manual endpoints either, the existing inbound-only,
unarmed-mining behavior remains. Automatic startup is unchanged.

Manual-only initial reception requires witness/block service support, uses the
same header validation/PRESYNC service, retries low-work/rejected replies with a
bounded delay, handles connection replacement and responds to shutdown while
waiting. Existing manual connections keep their separate role, no inherited
permissions and no automatic chain-sync protection slot.

`ManualBootstrapTest` uses a real Java P2P server, independently mined regtest
block, separate databases and actual Spring wiring with `maxconnections=0`.
It verifies accepted block, RUNNING, mining readiness, one MANUAL connection and
no permissions/protection slot. A second test checks waiting for a handshake
does not declare readiness and can be cancelled. Baseline failed at genesis.
IntelliJ confirmed the old early-return branch with automatic networking false.
The intermediate fixture clock error was diagnosed as TIME_FUTURE and corrected
in the test; the production clock check was preserved. Agent breakpoints removed.

RPC `getpeerinfo.presynced_headers` reads the per-peer controller under its lock,
reports actual first-pass height during PRESYNC/REDOWNLOAD and -1 with no active
session. Real P2P tests cover 2000/4000/6000 and final cleanup, plus an authenticated
RPC test for -1. This is not an assertion of full RPC schema parity.

All 60 focused cases passed with zero skips/failures/errors:
`target/core-update-audit/core32-manual-presync-focused.log`.
The full Bitcoin/RVN package run also invokes native Core comparisons and writes
the exact method-name inventory when `bitcoin.core.rpc.inventory` is configured.
Its schema/behavior scope is explicitly narrower than full method equivalence.

Full Bitcoin/RVN packaging succeeded November 5 at 18:08:40 Bangkok: 5,189 total
cases, 5,177 passed, 12 opt-in skips, zero failures/errors. Native Core fee/RPC and
PRESYNC wire comparisons were included. Log:
`target/core-update-audit/core32-manual-presync-full-build.log`.
The later inventory-only test correction includes hidden Core commands and
categorizes missing methods; it does not change production code.
That final native comparison passed (one case, no skips/failures/errors):
`target/core-update-audit/core32-rpc-inventory-native.log`.
Inventory including hidden methods: 176 reference names, 52 local names, 124
missing and zero additional names. See the categorized
[method-name inventory](core32-rpc-method-inventory.md); schemas and behavior
are not inferred from matching names.

Other eight-direction work remains tracked in
[core32-eight-acceptance.md](core32-eight-acceptance.md).

Live verification on November 5 at approximately 18:19 Bangkok: exactly one
Bitcoin process (PID 126060), original IDEA GPU configuration, RUNNING,
height/headers 974660, IBD false, ten connections including two block-relay
peers. RPC reported zero privileged peers and presynced_headers=-1 for all ten
peers. Two saved anchors loaded; CUDA accepted shares (approximately 2.17 GH/s
at the recorded sample). Evidence:
`target/core-update-audit/core32-manual-presync-live-verification.json`.

Startup was longer because of persisted mempool restoration. A read-only JVM
thread snapshot at 18:17 showed the runnable main thread in
`ClusterLinearization.neighbors -> connected -> MempoolGraphPolicy.checkLimits
-> Mempool.admitRestored -> NodeValidationService.restorePersistentMempool`.
It confirms repeated whole-graph work during restoration, not an optimizer fix.
Evidence: `target/core-update-audit/core32-manual-presync-startup-threads.txt`.
The observed restoration bottleneck was subsequently repaired in the
[local admission-work batch](mempool-admission-local-work-core32.md).
The complete budgeted Core optimizer remains open under direction 4.
