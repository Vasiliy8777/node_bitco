# IBD policy comparison with Bitcoin Core 31.1

Reference: [Core v31.1 net_processing.cpp](https://github.com/bitcoin/bitcoin/blob/v31.1/src/net_processing.cpp).

The debugger reproduced a false window stall: windowEnd=3, block 0 requested,
block 1 unassigned, and a nonempty blocking peer returned. This was a fixture
reproduction; the user's live public-network slowdown has not been measured.

## Changes

- An unrequested block inside the window prevents window-stall attribution.
- The next block beyond the window must be missing and unrequested.
- Another ready peer must have zero in-flight blocks and be able to serve that
  next height. Busy peers alone cannot start the short stall timer.
- A genuine window stall disconnects its owner and releases all requests.
  Transport failures and peer-wide cleanup preserve work until a replacement
  is available. IBD block NOTFOUND does not release requests or reset their timers.
- Receiving a block from the tracked peer clears its stall timer, including
  out-of-order delivery. The timeout doubles up to 64 seconds after a stall
  and decays on each connected block using max(2, floor(0.85 * seconds)).
- Ordinary block download timeout disconnects the peer without probation;
  its budget remains targetSpacing * (1 + 0.5 * otherDownloadingPeers).
- Default HEADERS response timeout is 120 seconds, including the first response.
  The earlier six-second initial deadline was removed.
- Per-peer requests are limited to 16; configuration above 16 now fails.
  The default is 16. The coordinator's moving window is 1024 blocks, and the
  shared session budget is 1024 rather than the earlier 256.
- Network completion draining and request refill now run in a dedicated virtual
  thread, independently of ordered validation batches. Buffered results count as
  pending until consumed. A TCP regression blocks validation of block 1 and
  requires block 18 to be requested after block 2 arrives during that pause.
- Production peer eligibility resolves announced hashes against validated indexes,
  checks chainwork, branch ancestry, witness services and an unvalidated snapshot
  base. VERSION.startHeight does not establish availability. Each peer retains its
  best-known block and common ancestor; downloads stop at common-height + 1024,
  while availability remains queryable for the window + 1 stall probe.
- Limited-history peers are excluded during IBD. Afterwards their validated
  best-known block defines the 288-block horizon with Core's two-block race buffer.
- Request selection fills a peer's queue before visiting another peer. Latency
  EWMA remains diagnostic only. Fixtures requiring two independent owners set
  an explicit one-request limit, rather than assuming alternating assignment.
- Reconnecting peers independently announce their known tip in response to a
  locator probe before resuming unfinished body downloads.
- Completed block synchronization stops its rate sampler after checking the
  committed tip. It prints a synchronized status instead of repeating 100% at 0/s.
  Incomplete IBD still reports zero rate and its starvation/validation diagnostics.

The October 1 performance report describes earlier 32/128-request settings;
those settings and measurements do not describe the current production policy.

## Verification

```powershell
.\mvnw.cmd test
.\mvnw.cmd -pl app -am '-Dtest=BitcoinCoreMiningRoundTripTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dibd.benchmark.core=true' '-Dbitcoin.core.binary=C:/Program Files/Bitcoin/daemon/bitcoind.exe'
```

The opt-in benchmark starts an isolated Core source, downloads its chain with
a separate Core receiver, then downloads the same chain with the Java node.
Each receiver's final block hash must match the source. Temporary regtest data
directories do not use the user's databases. Core timing includes addnode and
RPC polling; Java timing includes lifecycle startup and active-tip polling.
This measures small local regtest blocks, not public-network IBD.

Baseline validation before the independent network receiver and peer-chain policy:

- Full reactor: BUILD SUCCESS, 3881 tests, zero failures/errors, 6 skipped.
- Core 31.1 interoperability run: BUILD SUCCESS, all 3 opt-in tests passed,
  including mining/Stratum/relay/reconnect and the paired IBD benchmark.
- 8192-block paired benchmark: Java 12.422 seconds (659.5 blocks/s);
  receiving Core 67.926 seconds (120.6 blocks/s), identical final hash.
  These are elapsed times including connection/synchronization scheduling;
  they are not steady-state download throughput or a public-network comparison.
- Full-reactor synthetic network benchmark: 4096 blocks, one local peer,
  16 requests per peer, 8.265 seconds (495.6 blocks/s).

Logs: `target/ibd-core-policy/full-reactor.log` and
`target/ibd-core-policy/core-interop.log`. These are generated artifacts.

Continuation measurements:

- Combined full reactor plus Core interoperability: BUILD SUCCESS; 3888 tests,
  zero failures/errors, 3 skipped; 7 minutes 18 seconds. Reconnect resumes after
  the new peer answers the known-tip locator probe. Snapshot/crash recovery and
  the existing consensus validation suites passed.
- Focused policy, scheduler, coordinator and configuration run: 52 tests passed.
- Synthetic 4096-block loopback: 3.504 seconds, 1169.1 blocks/s, one peer with
  16 requests. The blocked-validation regression also passed.
- Core 31.1 paired 8192-block run: Java 8.571 seconds, 955.8 blocks/s;
  receiving Core 76.824 seconds, 106.6 blocks/s. Identical final hash. All
  3 opt-in mining/Stratum/relay/reconnect/IBD tests passed in this run.
- These elapsed times include startup and connection scheduling. Regtest bodies
  are small; public-network block sizes, disk costs and peer behaviour differ.

The combined reactor/Core command is:

```powershell
.\mvnw.cmd test '-Dbitcoin.core.binary=C:/Program Files/Bitcoin/daemon/bitcoind.exe' '-Dibd.benchmark.core=true'
```

Continuation logs: `target/ibd-core-policy/continuation-focused.log` and
`target/ibd-core-policy/continuation-full-core.log`.

## Shared requests and per-peer window traversal

- Every scheduler session now uses the same in-flight registry. Peer slot claims
  are atomic, limited to 16 across foreground and historical downloads, and a hash
  cannot be in flight twice. Timeout peer counts and oldest-request accounting see
  all sessions.
- Closing or failing one session releases only its own requests. It no longer
  removes another chainstate's ownership. Transport cancellation unregisters the
  dispatcher request before publishing a free hash/slot; a subsequent session can
  request the same hash without a stale registration.
- Stall detection now runs for each idle peer with a window+1 matching that peer's
  common ancestor. The scanner checks tree validity/witness capability before
  available bodies and in-flight ownership, and skips missing old blocks outside
  limited-history retention. Window+1 is checked before that retention filter, as
  in Core's FindNextBlocks. A peer cannot be reported as blocking its own window.
- Focused scheduler/window/peer-policy/coordinator regression: 55 tests passed.
  Logs: `target/ibd-core-policy/peer-window-regression.log`.
- Current Core 31.1 paired 8192-block run: Java 7.979 seconds (1026.6 blocks/s),
  receiving Core 79.406 seconds (103.2 blocks/s), identical final hash. All three
  opt-in interoperability tests passed. This remains a small-block local regtest
  elapsed-time measurement, including connection scheduling.

Traversal ordering is taken from Core v31.1 `FindNextBlocks`, lines 1381–1419:
valid tree/witness → available body/active chain → global in-flight owner →
window end → limited-history range → fetch candidate. The per-peer window probe
uses the refreshed common pointer and its +1025 height; it cannot interpret work
inside an already advanced peer window as a window stall.

## Remaining differences

### Pending availability regression

The scheduler formerly treated a ready peer whose HEADERS availability proof
was pending as exhausted after another peer returned NOTFOUND. A TCP regression
now delays the second peer's eligibility, retains the logical request, then
confirms eligibility and completes the download. The test failed before the fix
with `Unable to download block` and passes afterwards. Foreign-session ownership
also keeps an otherwise unassigned request pending. Only ready peers that
actually attempted and failed the request qualify as exhausted.

Validation: 64 targeted sync/lifecycle tests passed in
`availability-wait-after.log`; the complete p2p reactor and dependencies passed
in `availability-wait-p2p-suite.log`, including a regression preserving the
Java NOTFOUND exhaustion behavior. These are subsequent checks; the combined
Core benchmark below predates this additional fix.

### Core block NOTFOUND handling

Core v31.1's NOTFOUND handler (net_processing.cpp lines 4743–4756) processes
transaction inventories only. Production IBD sessions now register Core block
requests, which retain the dispatcher future, owner and download timer after
block NOTFOUND. Delivery completes that same request; disconnection or the
normal scheduler timeout releases it for reassignment. Cancellation unregisters
the request-specific policy. Direct synchronous download APIs retain their
existing NOTFOUND error contract; they are not used by the production IBD
scheduler. The older exhaustion regression above was replaced with ownership
retention/delivery and disconnection/availability tests.

Validation: `core-notfound-p2p.log` passed 543 p2p/dependency tests;
`core-notfound-sync-repeat.log` passed 86 dispatcher/scheduler/sync/lifecycle
tests, including both silence and NOTFOUND before the ordinary download timeout.
The first sync invocation was stopped because its old application fixture
expected terminal NOTFOUND failure; that fixture now asserts retained ownership.
The earlier Core throughput figures were not rerun for this change.

These corrections do not establish the cause of the earlier intermittent
lifecycle timeout or complete parity for other IBD algorithms.

Final combined repeat: `shared-window-full-core-repeat.log`, BUILD SUCCESS,
3894 tests, zero failures/errors, three skipped, seven minutes. Core-backed
8192-block download: Java 7.956 seconds (1029.7 blocks/s), reference receiver
74.167 seconds (110.5 blocks/s); final hashes match. Synthetic 4096-block run:
3.469 seconds (1180.7 blocks/s).

The first combined run had one reconnect fixture timeout. The debugger run,
isolated lifecycle class, and combined repeat passed; its intermittent cause
has not been established. The fixture now propagates assertion failures from
its simulated peer instead of obscuring them as a completion timeout.

This change does not constitute a complete port of Core's IBD algorithms.
The coordinator submits only its selected best-header connection path; Core can
walk each peer's branch and advance its common pointer over downloaded, linked
blocks before active-chain validation finishes. The Java coordinator still also
bounds exposure by its ordered validation frontier. Foreground/historical request
priority still requires comparison with Core's SendMessages selection order.
The hash announcement is coalesced, rather than processing every availability
event into per-peer state immediately. Locator probing is a Java integration
mechanism, not Core's complete per-peer header-sync lifecycle.
Received bodies remain buffered in memory until ordered validation; Core's
storage-backed block processing and disk-flush scheduling require comparison
using full-size blocks as well as small regtest bodies.
Further comparison is required for low-work headers presync/redownload (the Java
`HeadersPresyncState` contains SHA-256 commitments in 2000-header segments but is
not wired into `HeaderSyncCoordinator`), total headers deadlines, outbound
protection and eviction.

PoW, header, transaction and block-connection validation checks are not weakened.
A passing reactor or local interoperability test cannot certify all consensus
rules or equal public-network throughput. Those claims require separate
differential consensus tests and matched real-network measurements.
