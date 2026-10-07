# Reuse verified window ancestry across block download and peer policy

The previous live run still spent 1402.9 ms on 247 skip-index reads while processing
768 blocks. Coordinator preparation and peer branch-membership checks independently
resolved ancestors. Bitcoin Core follows in-memory parent/skip links in
[GetAncestor](https://github.com/bitcoin/bitcoin/blob/v31.1/src/chain.cpp); the Java
equivalent incurred cold native reads even for a path already prepared for download.

## Change

StoredBlockIndexLookup.ancestorRange resolves the end against the exact selected
root, follows parent hashes and verifies heights throughout the bounded range.
Only after the complete range succeeds does it publish immutable ancestry results
keyed by root hash and target height. BlockSyncCoordinator uses this method after
its existing bounded height-hint prefetch. Subsequent peer-policy ancestor lookups
can reuse the verified range. Height hints alone cannot establish membership.

The shared cache holds at most 8192 entries, or the configured index-cache entry
count if smaller. A disabled index cache disables this ancestry cache as well.
Eight prepared roots are retained to support short header-tip extensions: a newer
peer tip must first resolve to the exact prepared root hash before its range is
reused. For an older peer tip, the prepared root must resolve to that exact older
tip hash instead; all requested heights are already constrained to that peer tip.
Equal-height alternative branches do not match. Cold extension/root records
can still require ordinary lookup; warmed short extensions require no native read.

No eligibility, failed-branch state, body availability, script result or UTXO is
cached here. Those checks remain live in the existing peer policy/validation paths.
Root-hash keys protect against same-height reorganizations. Range length stays
within the existing 32768-index materialization bound. Failed or cancelled range
preparation does not publish a partial range; positive index reads remain harmless
and unknown records are not negative-cached. The existing shared lookup supplied
through NodeConfiguration is reused without extra configuration or migration.

## Verification

New real-RocksDB regression prepares a range on one branch, then resolves all 101
heights with zero native index/skip reads. It checks equal-height forks, independent
range preparation on both branches and short newer tips bridged to their exact
roots. Additional tests cover cancellation, missing parents, subsequent recovery,
cache-hit cancellation, eviction and disabled caching.

The finalized bridge regression also checks older peer roots on both branches,
without rewalking their cached historical ranges. The targeted bidirectional run
passed in `target/ibd-core-policy/verified-ancestor-range-bidirectional-tests.log`.

The initial forward-only full reactor passed 3942 tests in 361 classes, zero
failures/errors and six skipped, BUILD SUCCESS in 2m29s:
`target/ibd-core-policy/verified-ancestor-range-full-tests.log`.
Its diagnostic testnet console is
`target/ibd-core-policy/testnet-verified-ancestor-range-forward-only.log`.
This run encountered a network frontier gap with 945 buffered bodies and a
subsequent Core-policy staller disconnection/retry; another interval processed
35192 transactions in 213 blocks. It is not a controlled comparison with the
mostly sparse blocks in preceding runs. The process was stopped before rebuilding.

The initial test compilation had a missing method return type, corrected before
verification. The first bridge read-counter assertion observed two cold parent-root
reads; the fixture now primes those tip records explicitly to isolate repeated
historical-range traversal, matching the shared loader's warm-tip scenario.
The implementation still permits cold short-extension reads.

## Final verification and public testnet observation

`target/ibd-core-policy/verified-ancestor-range-bidirectional-full-tests.log`:
BUILD SUCCESS in 2m35s, 3942 tests in 361 classes, zero failures/errors, six skipped.
The final version includes both bridge directions and passes Core integration,
reorg, crash recovery, coordinator, peer eligibility and configuration tests.

`target/ibd-core-policy/testnet-verified-ancestor-range-console.log`: existing
K:/BitcoinJavaNode/testnet3 database, no reset. After the first positive rate and
before shutdown: 196 one-second observations, heights 897655 to 917596, mean
displayed rate 101.38 blocks/s, median 128, and 19 zero-rate observations. The last
120 observations averaged 119.72 blocks/s with 11 zeros. Shutdown progress is
excluded. The run started with transaction-rich blocks; throughput remains variable.

At height 906903, an interval processed 768 blocks at 144.6 blocks/s and performed
52 skip-index reads/459.0 ms. The preceding iteration's example of 768 blocks had
247 skip reads/1402.9 ms. That is about 79% fewer skip reads in these observed
intervals, not a controlled estimate of total IBD speed improvement. Primary index
reads remained 774/3265.4 ms, and undo reads 1536/1125.4 ms. At height 898842,
276 blocks contained 16562 transactions; UTXO reads and reorganization preparation
were significant costs. All these costs coexist with network frontier gaps.

No HEADERS handling errors appeared in the final live log. PID 184276 was stopped
via the IDE and verified absent; STOPPED was logged at 14:51:36 local time. The
production database was preserved. This optimization reduces repeated historical
ancestry I/O; it does not address large cold UTXO reads or establish full Core parity.
