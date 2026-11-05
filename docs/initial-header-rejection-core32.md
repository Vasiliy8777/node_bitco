# Initial header rejection and failover — Core 32.1

Reference: [Core header/block rejection policy](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp#L1873-L1916).

The initial header race previously caught I/O failures but let header-validation
and malformed-header exceptions escape to the lifecycle's fatal startup handler.
A peer could therefore cause FAILED even while another known peer was available.

Initial sync now records these peer-local failures, excludes the endpoint from
the next header race and tries another source. TIME_FUTURE retains the connection
without a protocol penalty. Other header rejections use the existing
`Peer.disconnectForProtocolViolation` handler and its NoBan/manual exemptions.
Transport-error handling is unchanged. The best-header/minimum-work conditions
remain mandatory. Internal IllegalStateException on an open peer still fails
startup rather than being concealed as network noise.

`InitialHeaderRejectionTest` drives the complete lifecycle with a rejected source
and a responsive replacement. INVALID, TIME_FUTURE, CACHED_INVALID and malformed
header cases failed before the fix; the internal-error case already passed.
The test checks exact endpoint exclusion, supervision of the successful
connection, block synchronization and RUNNING, as well as protocol penalty
selection. The internal-error case verifies FAILED with the original cause and
no second race. Existing real-P2P and database suites retain input validation.

IntelliJ runtime evidence on November 5 at approximately 13:47 Bangkok showed
`BlockHeaderValidationException` reaching old `NodeLifecycleService.start:377`,
immediately before `fail(exception)`. The call stack and expression output were
captured without breakpoint errors. Only the test JVM was paused; production
was gracefully stopped before compilation. Agent breakpoints were removed.
Baseline log: `target/core-update-audit/core32-initial-header-rejection-before.log`.

All 50 selected cases passed with zero skips/failures/errors, including initial
lifecycle/catch-up/runner, live rejection/timeout, PRESYNC/database admission,
coordinator and real peer-access tests. Log:
`target/core-update-audit/core32-initial-header-rejection-focused.log`.
Packaging succeeded with tests skipped after that run on November 5 at 13:49:40
Bangkok: `target/core-update-audit/core32-initial-header-rejection-package.log`.

This does not implement manual-only initial bootstrap, Core's complete timeout
calculation/preferred-peer accounting, or every block/transaction failover path.
The existing no-known-address terminal behavior remains; this fix guarantees
peer-local rejection does not prevent trying an available alternative source.

Live verification on November 5 at approximately 13:53 Bangkok: original IDEA
`BitcoinNodeApplication GPU`, exactly one Bitcoin process (PID 113440), RUNNING,
height/headers 974633, IBD false, nine connections including two block-relay
peers. Two anchors loaded and RPC showed zero privileged peers. CUDA resumed
accepted shares at approximately 2.43 GH/s. Spring initialization took 169 seconds.
Evidence: `target/core-update-audit/core32-initial-header-rejection-live-verification.json`.
