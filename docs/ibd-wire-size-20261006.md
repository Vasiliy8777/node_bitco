# Wire sizing without serialized buffers during IBD

Reference: Bitcoin Core v31.1
[GetBlockWeight/GetTransactionWeight](https://github.com/bitcoin/bitcoin/blob/v31.1/src/consensus/validation.h#L118).
The weight formula remains strippedSize * 3 + totalSize. Unlike the previous Java
implementation, size calculation no longer constructs complete block/transaction
byte arrays just to read their lengths. Actual network/flat-file serialization is
unchanged, and every structural/contextual/witness/script validation still runs.

New serializedSize APIs count version/locktime, CompactSize widths, outpoints,
scripts, output values, witness marker/flag, each input's witness count (including
empty witnesses) and all witness items. Length-only accessors avoid cloning script
and witness byte arrays. Math.addExact protects accumulated sizes. Nothing is
cached, and no validation outcome is reused or inferred from a header hash.

## Evidence before applying the weight change

Automatic approval review initially rejected a structural-success cache because
skipping checks requires proving full block immutability; that proposal was not
applied. It also initially rejected replacing serialized lengths without evidence
of equivalent wire sizing. We then added an independent serialization-oracle test,
ran it successfully on unchanged production weight code, added size APIs without
switching weight callers, and verified these APIs against real byte serialization.
The weight change was subsequently allowed after this evidence was collected.

The oracle covers CompactSize boundaries 252/253, 65535/65536, 32-bit boundaries,
input/output/block transaction counts, script/item lengths, witness-stack counts,
empty witness stacks in mixed-input transactions, and 200 deterministic mixed
legacy/witness cases. Results are compared to existing actual serialization,
not merely to another instance of the new counting implementation.

Logs: `wire-size-equivalence.log`, `wire-size-api-tests.log`,
`speed-wire-weight-tests.log` (all BUILD SUCCESS). The latter includes weight,
block validation/processing, NodeValidationService and actual Core roundtrip/reorg.

## Matched size microbenchmark

`WireSizeEquivalenceTest.measuresAllocationOnIdenticalWitnessBlock`, enabled with
`-Dibd.benchmark.wire.size=true`, measures both methods on the same witness block
for 100 iterations after warmup and asserts equal summed lengths. In
`speed-wire-weight-full.log`, byte serialization allocated 239614456 bytes and
took 34.946 ms; size counting allocated 4102456 bytes and took 2.936 ms. This is
about 98.3% fewer allocated bytes in this fixture. Timing is illustrative and
JIT/environment dependent; it is not an end-to-end IBD or Core speed comparison.

Full verification after the final production/test changes:
`speed-wire-weight-full.log`, BUILD SUCCESS in 3m40s, 3928 tests across 360 classes,
zero failures/errors, five skips. Includes consensus, mempool, mining, networking,
actual Core roundtrip/reorganization and chainstate/snapshot process-crash recovery.
The oracle also covers block transaction-count CompactSize boundaries and all
CompactSize widths through Long.MAX_VALUE. This final full run supersedes the
earlier targeted checks for the wire-size change.

Public testnet3 verification used the existing K:\BitcoinJavaNode\testnet3 database.
`target/ibd-core-policy/testnet-wire-weight-console.log` contains 74 one-second
observations after the first positive rate: height 798494 to 809847, zero zero-rate
samples before shutdown. A representative predominantly simple interval processed
804 blocks at 158.1 blocks/s. The node stopped normally in 90 ms and PID 143656 was
verified absent. This is a short observational run on a new historical range, not
a matched before/after experiment; it does not prove an end-to-end speedup percentage.

## Remaining architecture work

The download horizon still depends on connected blocks. HAVE_DATA currently also
exists for stored invalid bodies, so safe receive-frontier advancement needs a
separate record of verified, linked bodies, mutation-sensitive error handling,
bounded buffering/backpressure and restart tests. This step does not establish
complete Core parity or claim that network starvation has been eliminated.
