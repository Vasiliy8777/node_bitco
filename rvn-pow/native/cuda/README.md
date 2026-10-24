# Local RVN CUDA KawPoW worker

`kawpow.cu` implements GPU DAG generation and KawPoW 0.9.4 nonce search.
`worker.py` uses Python's standard library, CUDA Driver API and NVRTC. No pool,
RPC, downloaded miner binary, Python package installation or network connection
is involved. The kernel is a port of this project's Core-vector-tested Java
implementation; no upstream miner source is vendored.

Requirements: Python 3, NVIDIA driver and CUDA Toolkit with NVRTC (`CUDA_PATH`).
The worker was exercised on RTX 4070 / CUDA Toolkit 12.2 / Python 3.13.5.
The packaged Spring Boot JAR also passed this diagnostic with isolated workspace
temporary paths, exited successfully, removed its light-cache transfer files and
released the GPU context. A 4096-nonce zero-target batch completed; that small
diagnostic is not a production hashrate benchmark.
It retains one full DAG in VRAM, releases the temporary GPU light-cache after
DAG generation, and checks free device memory including a configurable reserve
before allocation. It does not spill the DAG into host memory. Java retains
a separate bounded CPU light verifier, not a duplicate full DAG.

Build from the repository root:

```powershell
mvn -pl rvn-app -am package -B -q
```

Run the application with the worker diagnostic (working directory: repository
root; `ravencoin.mining.cuda.worker` can be an absolute path):

```powershell
java -jar rvn-app/target/rvn-app-0.0.1-SNAPSHOT.jar --ravencoin.mining.cuda.enabled=true
```

Or select IntelliJ configuration `RavencoinNodeApplication (CUDA Check)`.
`RVN_CUDA_ENABLED=true` enables the same diagnostic. It allocates real epoch
DAGs and runs four Core known answers (heights 0, 49, 50, 30000), uint64 nonce
boundaries, exact target equality and a bounded 4096-nonce search. Default node
startup does not initialize CUDA. This does **not** enable live mining.

`RvnCudaKawpowWorker.search` returns immutable CPU-verified candidates with native
Ethash mix/final byte order. Header input is the Core header-hash display bytes,
not an arbitrary SHA256d wire digest. Before constructing an RVN 120-byte header,
reverse the mix bytes into RVN wire order. Each candidate is recomputed by the
independent Java light verifier; incorrect nonce/mix/hash/target results fail
the worker. Calls are serialized; nonce ranges never wrap. Search requests are
bounded to 65536 nonces, responses to 128 candidates. `overflow=true` requires
the caller to retry smaller subranges; it must not discard those nonce ranges.
Close/cancellation kills the local process and releases its context. Deadlines
are 10 minutes for epoch preparation and 30 seconds for one search response.

The binary stdin/stdout protocol is internal to the Java bridge; JSON lines are
also available for diagnostics. It is not a network-facing mining protocol.
The current straightforward kernel prioritizes agreement with the Java verifier;
production hashrate, Windows watchdog behavior across GPUs, long-duration fault
recovery and GPU tuning still need measurement. The worker is not yet connected
to a validated mainnet job service or candidate relay.
