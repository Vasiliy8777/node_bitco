# Ephemeral dust: base fee and modified fee — Core 32.1

Reference: [`PreCheckEphemeralTx`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/ephemeral_policy.cpp)
rejects a dust-bearing transaction whenever either its base fee or modified fee
is nonzero. [`PackageChildWithParents` and `PackageTestAccept`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp)
use different fee-validation modes.

Java previously supplied only modified fee to its ephemeral-dust check. A fee
delta registered before admission could cancel a positive base fee, allowing a
dust parent plus fee-paying child which Core rejects. Admission now supplies
both base and modified fee; both must be zero when the transaction has dust.
Existing public single-fee overloads retain their former behavior for callers
without a separate delta.

Four real mempool/package regressions cover positive base fee cancelled by a
negative delta (rejected without mutation), zero base fee with either signed
nonzero delta (rejected), zero base and modified fee with a dust-spending child
(accepted), and cancellation on a non-dust parent (accepted).

The native differential fixture uses a mature isolated-regtest Core wallet coin, independently
signed parent/child transactions and a future-tx prioritisation of -1 sat on
both engines. The parent pays a base fee of 1 sat and has a zero-valued dust
output. Native `submitpackage` must reject that parent for dust, and Java must
reject the same signed package. Both mempools are checked for unchanged contents.
Core's multi-transaction `testmempoolaccept` does not enable package feerates,
so it cannot serve as the positive CPFP probe for this fixture.

Scope: this fixes the ephemeral-dust fee predicate, not block consensus. The
local internal reason remains `dust-with-fee`; Core reports `dust`. Native RPC
shape/reason parity and the discovered multi-transaction testmempoolaccept
fee-mode difference were recorded under the RPC workstream. The fee-mode
difference is now addressed by the [individual-fee follow-up](testmempoolaccept-fee-mode-core32.md);
full rejection shape/reason parity remains open.

Validation, November 6, 2026: all 539 mempool cases passed locally. The final
Bitcoin/RVN reactor passed 5,252 cases (5,240 passed, 12 opt-in skipped, zero
failures/errors), including the independently signed native submitpackage
rejection, both unchanged-pool checks and both application packages. Log:
`target/core-update-audit/core32-ephemeral-dust-fees-final-full-build.log`,
completed 12:53:09 Bangkok. The earlier `core32-ephemeral-dust-fees-full-build.log`
predates the corrected submitpackage fixture and is not the final validation.

Original IDEA `BitcoinNodeApplication GPU` restarted from `app/target/classes`,
mainnet/gpu, as PID 130760. Final RPC verification found height/headers
974766/974766, IBD=false, eight peers (two block relay), 23,069 mempool entries,
one Bitcoin process and no privileged peer. CUDA accepted shares at about
2.56 GH/s. Evidence:
`target/core-update-audit/core32-ephemeral-dust-fees-live-verification.json`
and PID-specific records in `target/mainnet-node.log`.
