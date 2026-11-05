# Live header rejection — Core 32.1

Reference: [Core block/header rejection policy](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp#L1873-L1916).

`LiveChainSynchronizer` previously closed a peer directly for every contextual
header rejection. This differed from the existing asynchronous HEADERS handler
and bypassed the peer's NoBan/manual protocol-misbehavior handling.

Live reception now rejects TIME_FUTURE without closing or discouraging the
sender. CACHED_INVALID also retains an inbound connection. Other header rejection
reasons use `Peer.disconnectForProtocolViolation`, preserving locally assigned
NoBan/manual exemptions and ordinary protocol-close bookkeeping. The loop then
tries the next ready peer. A rejection does not count as successful validation,
activate a block, change the header tree or make mining current by itself.

The six-case `LiveHeaderRejectionTest` failed before this change. Runtime evidence
from IntelliJ on November 5 at 13:10 Bangkok captured TIME_FUTURE immediately
before `peer.close()`, at old `LiveChainSynchronizer.replaceFailedPeer:176`.
Stack: `run:127 -> replaceFailedPeer:176`; evaluation had no breakpoint errors.
Only the test JVM was paused; production had been gracefully stopped before
compilation. Agent breakpoints were removed after evidence capture.

The fixture covers future time on inbound/outbound peers, cached-invalid on
inbound/outbound peers and other invalid headers on both directions. It checks
failover to a responsive neighbor, successful subsequent synchronization,
freshness, exact protocol-penalty selection and no direct close of a rejected
header sender. Existing real-P2P permission tests exercise the delegated
NoBan/manual behavior; real database tests retain mandatory header validation.

All 63 selected cases passed with zero skips/failures/errors, including initial
lifecycle, live synchronization, PRESYNC and peer-access/discouragement suites.
Log: `target/core-update-audit/core32-live-header-rejection-focused.log`.
Packaging succeeded on November 5 at 13:13:08 Bangkok with tests skipped after
the successful targeted run. Log:
`target/core-update-audit/core32-live-header-rejection-package.log`.

Initial-sync rejection/failover and full block/transaction rejection handling
remain separate work. This change does not claim complete Core equivalence.

Live verification on November 5 at approximately 13:17 Bangkok: original IDEA
`BitcoinNodeApplication GPU`, exactly one Bitcoin process (PID 111352), RUNNING,
height/headers 974631, IBD false, nine connections including two block-relay
peers. Two saved anchors loaded; RPC showed zero privileged peers. CUDA resumed
accepted shares. Spring initialization took approximately 215 seconds before
normal header/block synchronization. Evidence:
`target/core-update-audit/core32-live-header-rejection-live-verification.json`.
