# Nonce and extraNonce scheduling

Bitcoin CUDA mining uses a keyed uint32 bijection: add the key, apply unsigned
XOR shifts and multiply by two odd constants. RVN CUDA mining maps a uint64
counter through two mixed uint32 halves. In both cases a header-derived key is
fixed for the entire header. There is no per-nonce random-number generation on
the CPU, no visited-value table and no transfer of nonce arrays to the GPU.

`common/.../NoncePermutation.java` implements the forward and inverse mappings.
CUDA implementations use the same unsigned arithmetic. A returned nonce is
mapped back to its logical counter for range checks, while proof calculation,
CPU verification and network submission use the actual nonce. RVN's sixteen
cooperating CUDA lanes all use the same nonce for each logical counter.

Logical counters still advance sequentially. Thus ordered progress counters in
logs do not mean that the actual mining nonces are sequential. The RVN offline
benchmark labels these `firstCounter` and `nextCounter`.

Bitcoin extranonce1 and RVN coinbase extraNonce come from `ExtraNonceSequence`:
a fixed random session key plus a uint64 counter, passed through the reversible
SplitMix64 finalizer. Wraparound stops the sequence rather than repeating it.
RVN serializes the full unsigned extraNonce, including its upper bit. Bitcoin's
CUDA miner independently permutes its negotiated extranonce2 space (1..32
bytes); it advances on a new template and on nonce exhaustion, without resetting
the sequence on each template. It retains the header/counter on a wire-id or
difficulty-only update and does not repeat an already completed GPU range.

Uniqueness is guaranteed within a sequence under a fixed key, not across all
process restarts. Fresh session keys and new extranonce1 values separate new
connections. For multiple workers mining an identical header, use the **same**
key and disjoint logical counter ranges, or give each worker a distinct
extraNonce/header. Different keys alone do not make nonce ranges disjoint.
Overflow recovery intentionally retries the original range at a smaller batch
size; the mapping stays fixed so that no candidate range is skipped.

The algorithms are not cryptographic permutations and do not increase success
probability per hash. Existing target/mix checks and stale-job cancellation stay
in place. Raw sequential search is retained for known-answer diagnostics and
paired throughput comparisons; ordinary GPU mining uses the permuted path.

Validation commands (stop other GPU miners before running GPU diagnostics):

* `python -m unittest discover -s tools/gpu-miner -p "test*.py"`
* `python tools/gpu-miner/benchmark_nonce_permutation.py` checks Bitcoin GPU
  hashes against CPU, the known genesis proof, uint32 limits, overflow recovery,
  and alternating sequential/permuted throughput.
* `RvnCudaKawpowBenchmark worker.py tmp python HEIGHT permutation` runs mandatory
  Core-vector/uint64/CPU checks and paired measurements at the selected epoch.
* `RvnCudaMiningRoundSelfTest worker.py tmp python` performs the actual permuted
  GPU search, installs the proof and validates it independently on CPU with native
  transaction checks. It never opens peers or submits a network block.

## Measurements on RTX 4070, 2026-10-29

Six alternating two-second samples per mode, median throughput:

| Workload | Baseline | Permuted | Difference |
| --- | ---: | ---: | ---: |
| Bitcoin original CUDA kernel vs new scheduling | 1679.624 MH/s | 1655.058 MH/s | -1.463% |
| RVN epoch 613, sequential vs permuted | 23.722 MH/s | 23.726 MH/s | +0.019% |

The tiny positive RVN difference is noise, not a claimed speedup. Bitcoin's
before/after comparison loaded the original kernel from Git and the new kernel
into separate modules and alternated them without another mining process.
Logs: `target/bitcoin-nonce-before-after.log`,
`target/bitcoin-nonce-gpu-comparison-final.log`,
`target/rvn-nonce-gpu-comparison.log`, `target/rvn-nonce-gpu-round.log`.
