# Extra connections to a missing network

Implemented 2026-11-02 against Bitcoin Core 32.1
[`MaybePickPreferredNetwork` and `ThreadOpenConnections`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net.cpp).

With the standard eight full-relay outbound connections established, an
independent exponential timer (five-minute mean) can request one extra full-relay
connection to a network type missing from that pool. The network must have known
AddrMan addresses. The currently implemented automatic transports considered by
this probe are IPv4 and IPv6; Tor/I2P/CJDNS routing is not enabled by inferring it
from addresses alone.

Selection is constrained to the preferred network, including the existing
netgroup-diversity fallback. It never substitutes another network merely because
the preferred network has no available candidates. Existing endpoint exclusions,
bans, discouragement, handshake eligibility and services checks still apply.
The permission is consumed before selection/handshake, so failure waits for the
next interval instead of initiating a tight reconnect loop.

The probe shares the existing single additional slot with stale-tip full-relay
and periodic block-relay probes, including pending handshakes. It does not run
while the normal pool is incomplete, while a higher-priority probe has permission,
or with a configured full-relay target other than eight. It does not change the
normal pool size. Existing extra-peer eviction protects the only full-relay
connection to each network, allowing an IPv6 connection to replace a redundant
IPv4 connection while returning the pool to eight peers.

The configured connection capacity must leave room above the normal outbound
pool. The shared extra-slot guard now applies that capacity to all three kinds
of probes; for example `maxconnections=8` with eight normal full-relay peers
cannot open a ninth automatic connection.

Six new tests cover actual loopback handshakes with logical public endpoints,
retention of the newly represented network, already-represented networks, reduced
targets, a pool with no spare connection capacity, failed-attempt pacing,
constrained selection and bans. Existing recovery,
rotation, supervisor, manager, relay, Spring configuration and
`PeerWriteDeadlineTest` are included in the focused run.

The full Bitcoin/RVN reactor completed 5044 cases: 5032 passed, 12 opt-in cases
skipped, zero failures/errors (`core32-network-probe-full-build.log`). A legacy
SENDHEADERS test fixture advertised READY without a negotiated version and
produced background-thread errors despite passing assertions; its peer setup was
corrected before the final focused run. No production READY/handshake invariant
was relaxed to accommodate that fixture.

After adding the connection-capacity guard and excluding independent BIP133
control traffic from the block-announcement fixture's assertions, the final
28-case confirmation passed without background-thread exceptions and packaged
both applications (`core32-network-probe-final-confirmation.log`). It includes
all six network-probe cases, Spring configuration, SENDHEADERS relay and write
deadline tests.

The subsequent [scheduling phase](block-reception-and-scheduling-core32.md) moves
probe scheduling to a 500-ms pump, retaining 45-second extra-peer eviction. Network
protection currently counts automatic full-relay connections because there is no
separate manual connection role. This implementation does not claim the additional
privacy-network transport support supplied by Core.

Deployment follows the user's selected workflow: stop the single Bitcoin node
before compiling its classes, finish checks, then restart the original
`BitcoinNodeApplication GPU` IDE configuration with its existing profiles/data
paths. No additional deployment JAR or alternate run configuration is created.

PID 75736 stopped cleanly and saved two block-relay anchors before compilation.
After all checks, PID 68192 started through the original configuration and reached
`RUNNING` at 10:41:10 Asia/Bangkok. Authenticated RPC reported height/headers
974208, IBD false and eight peers including two block-relay peers. CUDA logged
2548.70 MH/s and six accepted shares. Process enumeration confirmed exactly one
Bitcoin node and absence of the removed packaged run configuration. Live RPC
evidence is `target/core-update-audit/core32-network-probe-live-verification.json`.
