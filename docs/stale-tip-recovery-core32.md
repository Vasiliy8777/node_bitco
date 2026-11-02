# Stale-tip outbound recovery

Implemented 2026-11-02 against
[Core 32.1 net_processing.cpp](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp),
specifically `TipMayBeStale`, `CheckForStaleTipAndEvictPeers` and the full-relay
part of `EvictExtraOutboundPeers`.

The supervisor checks every 45 seconds. The stale-tip decision runs at ten-minute
intervals, using a strict threshold of three network target spacings since the
last connected block. No timestamp from an untrusted block header substitutes
for local progress. Before any block connects, the first check initializes the
progress clock. Active scheduled block downloads, compact reconstructions and
relay-owned full-block fallbacks prevent a new stale-tip decision.

A stale tip enables one additional full-relay slot. It shares existing AddrMan
selection, endpoint/netgroup reservations, eligibility checks, handshake and
reconnect backoff. No unbounded connection loop or additional socket reader is
introduced. An unsuccessful candidate can be retried while the permission remains
enabled; successful eviction clears permission until the next stale check.

With more full-relay peers than the configured target, eviction chooses the
oldest useful new-block announcement, breaking ties by the newer connection.
It excludes up to four peers with validated header work at least equal to the
active tip and the only full-relay connection to a network type. Selection happens
before checking the victim's age and downloads: a selected peer younger than or
equal to 30 seconds, or with an active block download, postpones eviction rather
than sacrificing a different peer. Useful announcement credit requires a newly
indexed validated header above the active chainwork; replaying known headers or
sending arbitrary inventory does not earn that credit.

If an old pool member is evicted, the extra connection moves into its ordinary
slot. Close notification and reconnection remain supervised after that move.
Shutdown stops the recovery timer and workers before the ordinary peer-manager
shutdown. Restart-anchor persistence continues to include block-relay peers only.

Tests cover strict timing boundaries, fresh progress, disabled recovery, active
downloads, network/protected-peer exclusions, announcement ordering and tie
breaking. Isolated socket tests exercise temporary connection establishment,
download protection, eviction, useful-peer promotion and reconnection after the
promoted peer closes.

The full Bitcoin/RVN reactor completed 5025 cases: 5013 passed, 12 opt-in cases
skipped, zero failures/errors (`core32-recovery-full-build.log`). After the final
relay metadata test and shutdown/logging changes, 55 focused cases passed and
both applications were packaged (`core32-recovery-final-focused.log`). This
includes `PeerWriteDeadlineTest`, recovery/anchor socket tests, supervisor,
Spring configuration and relay tests. The final relay test verifies that a known
header replay earns no new credit, while validated work, active downloads and
connected-block progress reach the recovery view.

Live deployment used the saved `BitcoinNodeApplication GPU` configuration. PID
71472 stopped cleanly and saved two anchors. PID 73928 retained `mainnet,gpu` and
the existing data paths, consumed two anchors and reached `RUNNING` at 09:43:12
Asia/Bangkok. Authenticated RPC then reported height/headers 974201, IBD false,
six connections including two block-relay peers. CUDA logged 2560.41 MH/s and 14
accepted shares. A live thread dump confirmed the recovery scheduler and the
additional slot's waiting worker. No stale-tip condition was forced on mainnet;
probing/promotion/eviction were exercised by isolated socket tests instead.
Observations are in `target/core-update-audit/core32-recovery-live-verification.json`
and `core32-recovery-live-threads.txt`.

## Remaining differences

- This phase implemented stale-tip **full-relay** recovery. The subsequent
  [rotation and catch-up phase](outbound-rotation-core32.md) adds periodic extra
  block-relay connections, youngest-pair eviction and chainwork catch-up timeouts.
- The later peer-event phase assigns four-peer protection during validated
  asynchronous, initial and live header processing, rather than from periodic
  availability snapshots.
- The peer-event phase records connection identity/time at TCP establishment,
  before handshake. Existing grace comparisons still have whole-second precision.
- There is no separate manual connection role in this application; network
  protection counts the managed automatic full-relay connections.
- The subsequent reception/scheduling phase moves catch-up checks to a 500-ms
  pump; extra-peer eviction and stale-tip checks retain their separate timers.

These operational policies do not alter block consensus or mining acceptance
rules. They do not establish complete Bitcoin Core equivalence.
