# Ordinary INV fee filter — Core 32.1

Reference: [`SendMessages`, ordinary transaction inventory](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).
Core resolves mining-score/topological order first, then compares each entry's
base fee to the received filter's required fee for its adjusted virtual size.
Mining fee deltas must not affect this comparison.

`currentRelayMiningOrder` previously supplied `entry.feeRate()`, which uses the
modified fee. Consequently, a positive prioritisation delta could announce a
transaction below the remote fee filter, while a negative delta could suppress
one whose base fee satisfies it. The relay snapshot now supplies the integer
rate derived from **base fee and sigops-adjusted vsize**, retaining the original
mining order. Pending inventory is still refreshed at its actual send slot.

For nonnegative integer fees and received integer sat/kvB filter R, comparing
`floor(base_fee * 1000 / vsize) >= R` is equivalent to
`base_fee >= ceil(R * vsize / 1000)`, matching Core 32.1's upward rounding.
Consensus, mempool admission and mining priority calculations are unchanged.

The regression exercises the actual relay worker and encoded INV messages with
a mocked admitted mempool snapshot. Four entries cover positive and negative
deltas, exact/below filter boundaries and mining-order preservation: at filter
1001 sat/kvB, 100-sat/100-vB entries are excluded regardless of a +10000-sat
mining delta, while a 101-sat entry remains eligible with a -100-sat delta.
The test then lowers the filter to 1000 and confirms the exact 100-sat boundary.
This is a local relay regression, not an independent native wire comparison of
the new scenario. Broader open compatibility scope is recorded in
[the acceptance checklist](core32-eight-acceptance.md).

Validation on November 6, 2026: all 44 focused cases passed, followed by 5,225
full-reactor cases (5,213 passed, 12 opt-in skipped, zero failures/errors).
The full run includes native Core 32.1 compatibility and both application
packages. Full log: `target/core-update-audit/core32-ordinary-base-fee-full-build.log`,
completed at 01:15:48 Bangkok. The new scenario itself is covered by the local
encoded-INV regression described above, not a new native Core wire fixture.

Original IDEA `BitcoinNodeApplication GPU` restarted as PID 130828 from
`app/target/classes`, mainnet/gpu. RUNNING was observed at 01:16:44; the 01:17
RPC check found height/headers 974688/974688, IBD=false, eight connections
(two block relay), 37,866 mempool entries, exactly one Bitcoin process and no
privileged peer. CUDA accepted shares at approximately 2.47 GH/s. Live evidence:
`target/core-update-audit/core32-ordinary-base-fee-live-verification.json` and
PID-specific records in `target/mainnet-node.log`.
