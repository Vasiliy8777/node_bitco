# RPC and template construction update, 2026-10-13

## Changes

- `getblock` supports verbosity 0 through 3. JSON includes coinbase metadata
  and stripped size. Historical undo supplies non-coinbase fees at verbosity
  2/3 and prevouts at verbosity 3. Missing undo omits those fields; invalid
  undo counts produce an RPC error. Block body and undo are loaded under the
  same chain monitor to avoid races with pruning/reorgs.
- `getpeerinfo` omits deprecated `startingheight`, following Core 31 defaults.
- `getmempoolinfo` omits unmeasured JVM memory `usage` and conservatively reports
  `optimal=false` for nonempty pools. The size budget is still virtual bytes,
  not a JVM memory budget. Script JSON currently provides hex only, not Core's
  assembly/address/descriptor fields.
- Cluster discovery builds bidirectional dependency adjacency once per call,
  rather than scanning the entire pool for each visited transaction.
- Template selection uses a priority queue of cluster chunk cursors instead of
  rescanning all cursors for every selected chunk. Fee comparison, dependencies,
  weight/sigops limits and original cluster order for ties are preserved.

## Runtime evidence

Before the template optimization, `getmempoolinfo` exceeded a 20-second timeout.
A `jcmd Thread.print` snapshot showed `stratum-templates` executing
`ClusterLinearization.connected` while holding the ChainState monitor, and
the node lifecycle waiting for that monitor. IntelliJ had no active debugger
session; the thread dump provided runtime evidence without relaunching the node.
Dump: `target/rpc-live-thread-dump.txt`.

The old process stopped normally (`STOPPING -> STOPPED`, GPU worker stopped).
The updated executable JAR was started with `mainnet,gpu`, `-Xms4g -Xmx8g`
and `bitcoin.chainstate-directory=data/ssd-chainstate`, preserving the prior
launch settings. Session details are in `target/node-session.json`.

After restart, RPC reported blocks=headers=971213, initialblockdownload=false,
miningready=true. With 10250 mempool transactions, `getmempoolinfo` completed in
438 ms. These are individual observations, not latency guarantees. Fresh logs
contained no ERROR entries and the GPU accepted shares at about 2.57 GH/s.

## Verification

The final reactor `verify` run passed: 3988 test cases, zero failures/errors,
four skips. It enabled the installed Core binary, pre-mainnet/GPU checks and
both Core/UTXO benchmarks. Log: `target/rpc-full-verification.log`.

An additional run of `WireSizeEquivalenceTest` with
`ibd.benchmark.wire.size=true` passed all four cases, including the optional
allocation benchmark skipped by the reactor. Log: `target/rpc-wire-verification.log`.
Thus three unique test cases remain unexecuted: the legacy P2P/header integration
tests that require pre-existing Core chains on port 18444 with pinned hashes.
They expect different fixtures and are not covered by the current environment.
The isolated Core round-trip, 8192-block IBD benchmark, live pre-mainnet checks,
GPU/Stratum tests and crash/recovery tests did run successfully.

Regression coverage includes Core's mature coinbase-spend fixture, fee/prevout
omission without undo, malformed undo, connected components in both directions,
multi-parent/duplicate graph edges, linear graph traversal cost, fee-order/tie
selection and oversized chunks. Selecting 10000 independent transactions took
144.1 ms in the final reactor (synthetic workload, not a production benchmark).
