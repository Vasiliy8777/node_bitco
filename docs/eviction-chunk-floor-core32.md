# Eviction chunk weight and rolling floor — Core 32.1

References: [`TrimToSize` / `trackPackageRemoved`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/txmempool.cpp),
[`ToFeePerVSize`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/policy.h),
[`CFeeRate::GetFeePerK`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/feerate.h)
and [`FeeFrac::EvaluateFeeDown`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/util/feefrac.h).

Core converts the removed chunk's aggregate adjusted weight to vsize once,
then computes its modified fee rate and adds incremental relay fee to the
rolling floor. Java instead summed each transaction's individually rounded
vsize, which could understate the removed rate. `MempoolGraphPolicy.trim` now
uses `ceil(sum(adjusted_weight) / 4)` for this conversion. Chunk selection and
the local capacity accounting model are otherwise unchanged.

The ordinary and package admission commit paths previously bumped the rolling
floor only when `removedRate > 0`. That confused no eviction with eviction at a
zero rate. They now detect an actual reduction in entry count; a zero-rate
eviction still adds the configured incremental fee. Reorg reconciliation
already tracked actual removal this way.

Negative modified rates from local fee deltas are also retained until the
increment is added. Signed division rounds toward negative infinity, matching
Core's EvaluateFeeDown. BigInteger multiplication avoids intermediate long
overflow when the resulting sat/kvB rate still fits in a long. For example,
floor(-1000/101) is -10; with a 1000 increment the floor becomes 990, not 1000.

Five local regressions cover aggregate-weight boundaries (802/803/804/805 WU),
sigops-adjusted weight and modified fee, actual zero-fee eviction raising the
floor through the real mempool admission path, signed negative rates, and a
large local delta whose scaled intermediate would overflow a long. The zero
case also verifies that no eviction leaves the rolling floor unchanged.

This does not implement Core's allocator-specific DynamicMemoryUsage budget,
its bounded optimizer or the complete eviction tie/fallback policy. An
independent native memory-pressure fixture for these exact rounding/sign cases
remains open. The full native compatibility suite is separate evidence.

Validation, November 6, 2026: 527 mempool cases passed in the final focused run.
The full Bitcoin/RVN reactor passed 5,240 cases (5,228 passed, 12 opt-in skipped,
zero failures/errors), including all five new cases, native Core 32.1
compatibility and both application packages. Full log:
`target/core-update-audit/core32-eviction-floor-full-build.log`, completed
11:03:39 Bangkok. Original IDEA `BitcoinNodeApplication GPU` restarted from
`app/target/classes`, mainnet/gpu, as PID 137688. RUNNING was observed at
11:04:40; RPC height/headers 974758/974758, IBD=false, seven peers (two block
relay), 23,191 mempool entries, one Bitcoin process and no privileged peer
were verified. Evidence:
`target/core-update-audit/core32-eviction-floor-live-verification.json` and
PID-specific records in `target/mainnet-node.log`.
Accepted CUDA shares were observed again at 11:05.
