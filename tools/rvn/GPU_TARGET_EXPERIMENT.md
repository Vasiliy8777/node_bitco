# Real-target KAWPOW experiment

## IntelliJ Spring Play

Select **Ravencoin GPU Target Benchmark** in the run configuration selector and
press Play. The separate `RvnGpuBenchmarkApplication` starts only the experiment.
Stop other GPU mining processes first. Defaults are in
`rvn-app/src/main/resources/application-rvn-benchmark.yml`: the captured real block
from 2026-10-29, one hour of search, and `target/rvn-benchmarks` for output.
Each launch creates a new artifact directory; the previous run is preserved.
Progress appears in IDEA's Run console and `target/rvn-benchmarks/benchmark.log`.
The saved snapshot freezes that block's target; it is not automatically refreshed.

Override settings with Program arguments, for example:
`--ravencoin.benchmark.seconds=600 --ravencoin.benchmark.snapshot=path/to/block.bin`.
`--ravencoin.benchmark.dry-run=true` validates configuration without using the GPU.
Create `stop-requested` in the artifact directory printed at startup to finish
gracefully; the Spring process exits when the benchmark returns.

## Standalone launcher

`RvnRealTargetGpuBenchmark` is an explicit standalone GPU test, not an automatic
JUnit mining loop. It never opens chainstate and never submits a block.

1. Build `rvn-app` and its dependencies. Run `RvnRealTargetGpuBenchmarkTest` for
   deterministic checks of probability math and template/witness preservation.
2. Capture a recent block using `capture <peer> <display-block-id> <snapshot.bin>`.
   Capture independently checks its PoW, id, Merkle root and witness commitment.
   The block id can be taken from the node's latest verified header log.
3. Save the node's runtime classpath to `target/rvn-benchmark-classpath.txt` and
   stop the node gracefully. The launcher refuses to compete with a running node.
4. Run `tools/rvn/Start-RvnGpuTargetBenchmark.ps1 -Snapshot <snapshot.bin>
   -OutputDirectory <new-directory> -Seconds 3600`. Set seconds to zero only for
   an intentionally unlimited experiment.

The experiment keeps actual transactions, witness data, version, parent, height,
timestamp and nBits. It changes the coinbase extraNonce and recomputes the Merkle
root so the input is fresh, rather than replaying the source block's known proof.
GPU nonce/mix are cleared. Output logs include actual transaction count, serialized
and stripped sizes, block weight, target, epoch, launch/search times, job count,
batch count, tested nonces, hashrate, mean/median/p95 estimates and a found proof.
The current epoch is sampled against CPU after mandatory Core-vector self-tests.
Every returned target hit is independently recomputed by the existing CPU verifier.
Found blocks are written to `benchmark-found-block.bin`; snapshot/template files
are preserved. Logs use UTC (add seven hours for Asia/Bangkok).

This is an offline alternative to the captured block, not a live mining candidate:
the parent, timestamp and target remain frozen even as the network advances.
Transaction UTXO/script admission is not repeated by this benchmark. It does not
claim a block would be accepted by the current network or pay a reward.

For an approximately uniform 256-bit hash, success probability per nonce is
`(target + 1) / 2^256`. Mean work is its reciprocal; time is geometric, approximately
exponential. Mean time is not a deadline. With elapsed time t and mean m,
`P(found by t) = 1 - exp(-t/m)`, median is `m*ln(2)`, p95 is `-m*ln(0.05)`.
Transaction count affects template preparation; it does not multiply per-nonce
KAWPOW work, which takes the fixed-sized header digest, height/epoch and nonce.

To interrupt gracefully, create `stop-requested` in the experiment output
directory. The node is not restarted automatically when the experiment finishes.
