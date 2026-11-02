# Peer timer precision

Implemented 2026-11-02 against Core 32.1
[extra-peer grace checks](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp),
[connection scheduling](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net.cpp)
and [exponential duration rounding](https://github.com/bitcoin/bitcoin/blob/v32.1/src/random.h).

TCP establishment, supervised connection age and block/network probe deadlines
now retain epoch microseconds. No whole-second conversion occurs between socket
adoption, slot installation/promotion and extra-peer grace checks. Full-relay
eviction requires strictly more than 30,000,000 microseconds; block-relay eviction
permits exactly 30,000,000 microseconds. For example, a connection established at
100.750000 seconds cannot be removed at 130.500000 seconds by rounding its start
down to 100 seconds.

Independent five-minute exponential intervals are rounded to the nearest
microsecond, including legitimate zero-length draws. Their production samples
use `SecureRandom`, replacing the previous thread-local noncryptographic source.
The distribution/rounding behavior is intended to correspond to Core; this does
not claim identical random streams or identical seeded samples to Core's RNG.
The connection pump retains its 500-ms cadence and strict `now > deadline` test.
That polling cadence can delay execution after a deadline, but no longer replaces
the deadline with an integer-second approximation.

Core's second-valued timestamps and policies remain in seconds: useful-block
announcements, block-source credit, stale-tip progress/checks and the chainwork
catch-up state machine. Extra-peer eviction scheduling still uses a separate
monotonic 45-second interval. Public second/millisecond connection accessors are
retained as projections of the precise stored timestamp.

Tests cover both sides of the 30-second boundary at one-microsecond distance,
fractional connection creation times, nearest-microsecond exponential samples,
and a deterministic 100,000-quantile mean check. Gated-clock socket tests exercise
both block-relay and missing-network probes at deadline minus one microsecond,
at the deadline, and one microsecond after it. The injected clock and interval
hooks are package-private test configuration; production uses the actual clock
and cryptographic random source. Normal connection limits and the single shared
extra-slot allowance are preserved.

The full Bitcoin/RVN reactor completed 5063 cases: 5051 passed, 12 opt-in cases
skipped, zero failures/errors (`core32-timing-precision-full-build.log`). The
preceding targeted run also passed (`core32-timing-precision-focused.log`). The
full run includes transport/write-deadline tests, fractional probe socket tests,
peer protection/ordering tests, block synchronization and the native Core
consensus/fee comparisons. No background-thread exceptions appeared.

## Remaining limits

Core's message-processing loop and this implementation's 500-ms pump have different
scheduling architecture. JVM/OS scheduling and physical clock resolution still
bound when work actually executes. Manual connections, privacy-network transports,
full RPC coverage and complete historical mainnet replay remain outside the
demonstrated correspondence. Complete Bitcoin Core equivalence is not claimed.

Deployment uses the original `BitcoinNodeApplication GPU` configuration. The
single Bitcoin node is stopped before compilation and restarted after checks;
no alternate deployment JAR is introduced.

For this update PID 77728 stopped cleanly before compilation. The tested build
started as PID 55596 using the original IDE configuration. Initial peer failover
completed automatically; the node reached `RUNNING` at 16:30:43 Asia/Bangkok.
Authenticated RPC reported height/headers 974237, IBD false and seven connections
including two block-relay peers. CUDA reported about 2.54 GH/s and nine accepted
shares. Process enumeration confirmed exactly one Bitcoin node. Live evidence is
`target/core-update-audit/core32-timing-precision-live-verification.json`.
