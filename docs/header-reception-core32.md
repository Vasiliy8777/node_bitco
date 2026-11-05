# Useful header announcements across receivers

Implemented 2026-11-02 against Bitcoin Core 32.1
[`UpdatePeerStateForReceivedHeaders` and compact-block processing](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

Core updates the peer's last useful block-announcement time when a newly learned
header has strictly more chainwork than the active tip. Extra full-relay peer
eviction uses this time. Inventory or repeating an already-known header is not
sufficient to receive this credit.

Previously this application credited asynchronous HEADERS/compact-block header
processing, while synchronous initial and live header downloads did not. The
asynchronous handler also checked whether the header was known before entering
the serialized header processor, allowing competing receivers to observe the
same unknown header.

`HeaderBatchProcessor.Reception` now carries validated indexes and whether its
last header was newly created. It returns only after the complete batch commits;
invalid batches cannot publish a receipt for their valid prefix. `HeaderSyncService`
serializes this receipt with other header batches. The existing list APIs delegate
to this path. Relay uses the receipt under the existing validation chain lock.
The synchronous coordinator emits a separate new-header event after successful
processing, before its ordinary complete-batch observer. Initial and live sync
use this event to record announcements only above the active-tip work and outside
failed branches. Known-tip confirmations still notify the chain-sync protection
observer without claiming new-header credit. Empty replies emit neither event.

The network-facing service also checks continuity before invoking the batch
processor: each header after the first must name the preceding header's hash.
Two independently valid branches cannot be combined in one HEADERS response.
Rejection happens before persistence or progress/announcement callbacks, matching
the rule in Core's `CheckHeadersAreContinuous`. The general chain batch API
continues to expose individual header processing separately from this network rule.

Tests cover competing synchronous/relay receivers (exactly one new-header receipt),
replays, empty batches, all-or-nothing persistence on a bad final header, streaming
new-header events, known-tip suppression and failed-validation suppression. The
existing relay test verifies one announcement credit across repeated deliveries.
An additional real-database test rejects a mixed-branch response without saving
either unknown header, then accepts a continuous competing branch.

This change does not reproduce Core's low-work headers presync state machine or
establish complete Core equivalence. JVM scheduling and header/block activation
architecture retain the limits documented in the compatibility audit.

The original single `BitcoinNodeApplication GPU` IDE launch is retained. No
separate runtime JAR or alternate launch configuration is deployed.

Full reactor verification: 5,076 total cases, 5,064 passed and 12 opt-in skips,
zero failures/errors (`core32-header-reception-full-build.log`). The network
continuity guard was added after that run compiled the application. Its final
focused verification and packaging are recorded separately in
`core32-header-reception-final.log`; the original unconnected-header fixture now
uses a continuous orphan branch, preserving its unknown-parent/failover checks.
All 69 final focused cases passed with zero failures/errors, including native Core
32.1's 77 block-acceptance scenarios inside one JUnit case, real sockets and peer
write deadlines. Both applications packaged successfully after the final guard.

Live verification at 18:43 Asia/Bangkok: the original IDE launch, PID 82052,
reached RUNNING. Exactly one Bitcoin process was present. Authenticated RPC
reported height/headers 974251, IBD false, eight connections including two
block-relay peers. CUDA reported approximately 2.60 GH/s and 16 accepted shares.
Evidence: `target/core-update-audit/core32-header-reception-live-verification.json`.
