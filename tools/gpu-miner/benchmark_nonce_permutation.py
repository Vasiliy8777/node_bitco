"""Explicit no-network GPU validation and paired scheduling throughput comparison."""
import statistics
import time
from cuda_backend import CudaHasher
from miner import self_test


with CudaHasher() as gpu:
    print(f'DEVICE {gpu.name}', flush=True)
    self_test(gpu)
    header, count, counter = bytes(80), 262144, 0
    rates = {'sequential': [], 'permuted': []}
    for trial in range(6):
        modes = ('sequential', 'permuted') if trial % 2 == 0 else ('permuted', 'sequential')
        for mode in modes:
            search = gpu.search if mode == 'sequential' else gpu.search_permuted
            search(header, 0, 0, count)
            began, hashes = time.perf_counter(), 0
            while time.perf_counter() - began < 2:
                found, overflow = search(header, 0, counter, count)
                if found or overflow:
                    raise RuntimeError('Unexpected zero-target result')
                hashes += count
                counter = (counter + count) % (1 << 32)
            rate = hashes / (time.perf_counter() - began) / 1e6
            rates[mode].append(rate)
            print(f'TRIAL {trial+1} mode={mode} MHs={rate:.3f}', flush=True)
    raw, mixed = (statistics.median(rates[m]) for m in ('sequential', 'permuted'))
    print(f'RESULT sequentialMHs={raw:.3f} permutedMHs={mixed:.3f} deltaPercent={(mixed/raw-1)*100:.3f}', flush=True)
