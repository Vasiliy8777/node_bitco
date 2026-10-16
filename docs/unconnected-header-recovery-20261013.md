# Unconnected header peer recovery, 2026-10-13

## Failure and correction

At 16:00:09 the live node transitioned from RUNNING to FAILED after a received
header referenced unknown parent
`00000021cce0cd4553ffc5545f38c6ec2696783f87a1252818f07c52bf9b362b`.
HeaderProcessor threw a generic IllegalStateException; live synchronization
correctly preserves generic state failures for open peers, so the exception
escaped to the lifecycle runner. The outbound supervisor stopped and GPU work
became unavailable. The exact offending peer cannot be established from that
stack trace alone.

HeaderProcessor now throws the specific UnconnectedHeaderException, retaining
its IllegalStateException superclass for existing callers. HeaderSyncCoordinator
converts only this error to an IOException with the received header and parent
hashes. Both initial and live synchronization already replace peers on that
path. Other local storage/programming state errors remain fatal; errors are not
classified by message matching. Live failure logs now identify the peer address.

The header is still rejected. No validation rules, persistent chain data or
peer ban policy were relaxed. A rejected batch is not partially committed.

## Verification

UnconnectedHeaderRecoveryTest checks:

- A valid prefix followed by an unconnected header fails with an IOException
  whose cause contains the correct hashes, leaves index/tip persistence unchanged,
  and allows a subsequent valid response to advance headers.
- An unrelated IllegalStateException propagates unchanged.
- An open READY peer sending an unconnected header is closed, while live
  synchronization continues with the healthy peer and confirms current work.

Focused header/coordinator/live-sync tests passed. The full Maven reactor
`verify` passed: 3991 cases, zero failures, zero errors, three skipped legacy
integration cases requiring unavailable fixed regtest fixtures on port 18444.
Installed Bitcoin Core round-trip, 8192-block IBD, pre-mainnet/GPU, Stratum,
UTXO/wire benchmarks and crash/recovery checks were enabled.

Logs: `target/unconnected-header-focused.log`, `target/unconnected-header-full.log`.
Executable: `app/target/app-0.0.1-SNAPSHOT.jar`.
Launch settings preserved: mainnet,gpu; Xms4g/Xmx8g;
chainstate `data/ssd-chainstate`. The active launch is recorded in
`target/node-session.json`, with output in `target/mainnet-node.log` and
`target/mainnet-console.log`.

After restart, process 84900 reached RUNNING and miningready=true. At 16:28,
blocks=headers=971224, initialblockdownload=false, nine peers were connected,
and the GPU accepted shares at about 2.69 GH/s. The new console log contained
zero ERROR entries at that observation. This confirms startup and resumed work;
the regression tests, rather than a recurrence on mainnet, validate recovery
from the specific unconnected-header response.
