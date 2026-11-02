# Block-relay rotation and chainwork catch-up

Implemented 2026-11-02 against Bitcoin Core 32.1:
[connection scheduling](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net.cpp),
[five-minute interval](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net.h),
[eviction and chainwork checks](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

After the active tip becomes recent enough for direct fetching (strictly newer
than twenty target spacings), periodic block-relay probing is enabled for the
rest of the session. Intervals use an exponential draw with a five-minute mean.
The normal pool must be filled first. Stale-tip full-relay probing has priority;
both kinds share a single additional connection allowance, including handshakes.
A block probe consumes its interval before selecting/connecting, so a failed
attempt does not create a rapid reconnect loop. Zero configured block-relay slots
disable periodic block probing.

Once the block-relay pool exceeds its target, the two newest connections are
compared. The newest is removed unless it delivered a block more recently than
the second-newest, in which case the second-newest is removed. Older connections
are outside this comparison. The selected victim must have been installed for
at least thirty seconds and have no block download in flight. No alternate peer
is removed when that check postpones eviction. A promoted connection retains
its header-sync state and normal close/reconnect supervision.

Every new supervised connection is offered an asynchronous headers request.
Unprotected outbound full-relay and block-relay connections must demonstrate
validated chainwork at least equal to the active tip. The catch-up state machine
stores the current work benchmark and its parent's block locator, waits twenty
minutes, requests headers once, then allows two more minutes. Both timeout
comparisons are strict. Reaching the active tip clears the timeout. Reaching an
older benchmark when the active tip has advanced starts a fresh twenty-minute
period against the new tip; mere progress below that benchmark does not.

The four previously protected full-relay connections stay protected when the
active tip advances. Block-relay connections do not receive that protection.
Availability uses validated indexed headers (including resolved announcements
from the initial header-sync path); VERSION height and unknown inventory hashes
do not prove work. Retained header references are checked against failed-branch
state again when read, so invalidated work cannot assign new protection or reset
an unprotected peer's catch-up deadline. Already-granted session protection
remains until peer removal. A cached immutable locator is shared for the same active tip.

Coverage includes timing boundaries, catch-up/reset/protection transitions,
exponential interval samples and youngest-pair ordering. Real loopback socket
tests exercise successful and failed block probes, download protection, useful
peer promotion, reconnect after promotion, and final-request/disconnect/replacement
of a lagging peer. Tests use logical public endpoints mapped to local sockets;
they do not force peer eviction or stale-tip conditions on mainnet.

The full Bitcoin/RVN reactor passed 5026 cases with 12 opt-in cases skipped and
zero failures/errors (`core32-rotation-full-build.log`, 5038 total). After the
failed-branch metadata correction, 68 focused cases passed and both applications
were packaged (`core32-rotation-packaged-focused.log`). This includes a gated
handshake test proving that a pending block probe prevents a simultaneous stale
full-relay probe. `PeerWriteDeadlineTest` also passed in both runs.

## Launch configuration

During the build, the previous development process (PID 73928), which loaded
directly from mutable `target/classes`, failed with a missing local record class
while Maven was replacing compiler output. It was stopped through Spring Admin
JMX. Compiling over a running development process must be avoided: stop the
single node before rebuilding its classpath, then restart it after the build.

The user explicitly requested the original IDE launch on 2026-11-02. The packaged
process (PID 66400) was stopped cleanly before launching the single replacement
through `BitcoinNodeApplication GPU`, using `app/target/classes`, the existing
`mainnet,gpu` profiles and the same data/SSD paths. The added packaged run
configuration and both deployment JAR copies were removed. Do not switch this
deployment to a separate JAR without a new user request.

The restored IDE launch, PID 75736, reached `RUNNING` at 10:23:16. Authenticated
RPC reported height/headers 974205, IBD false, seven peers including two block-relay
peers; CUDA logged 2512.07 MH/s and three accepted shares. Process enumeration
confirmed exactly one Bitcoin node. Latest evidence is
`target/core-update-audit/core32-original-launch-live.json`.

The earlier packaged verification process PID 66400 reached
`RUNNING` at 10:09:51 Asia/Bangkok. At 10:11 authenticated RPC reported
height/headers 974204, IBD false and seven peers including two block-relay peers.
CUDA reported 2534.38 MH/s and 41 accepted shares. The recovery scheduler remained
alive beyond its first 45-second interval with no recovery-check errors; both
additional slot workers were present. Evidence is stored under
`target/core-update-audit/core32-rotation-live-verification.json` and
`core32-rotation-live-threads.txt`.

## Scope of correspondence

The operational state transitions above are implemented, with these remaining
architecture differences:

- The subsequent [reception/scheduling phase](block-reception-and-scheduling-core32.md)
  moves probes and catch-up checks to a 500-ms pump while retaining 45-second
  extra-peer eviction. Random intervals remain rounded up to whole seconds.
- The later [peer-event phase](peer-events-core32.md) records TCP age/order before
  handshake and assigns four-peer protection on complete validated header events.
- There is no separate manual connection role. Network protection counts
  automatic full-relay peers.
- The subsequent [extra-network probe phase](extra-network-probe-core32.md) adds
  first connections to missing IPv4/IPv6 networks. Other network transports and
  separate manual-connection accounting remain outside the application.
- The reception phase adds new stored side-chain body credit, without treating
  context-pending bodies as active-chain progress or crediting duplicate delivery.

These are connection policies; block consensus and template acceptance rules are
unchanged. Complete Bitcoin Core equivalence is not claimed.
