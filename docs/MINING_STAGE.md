# Mining stage closure

The Mining stage is closed when the full reactor is green and the following contracts remain covered:

- coherent chain+mempool snapshots for template construction;
- contextual next-block time, difficulty and version selection;
- dependency-safe cluster/chunk transaction selection using signed modified fees;
- block weight and sigop budgets;
- consensus/base fees in coinbase reward (fee deltas affect ordering only);
- BIP34 coinbase height and SegWit witness commitment;
- BIP9/GBT `rules`, `vbavailable`, `vbrequired`, proposal mode and longpoll refresh;
- `submitblock` through the normal validation/relay path;
- mining readiness gated by synchronized/current chain state;
- Stratum V1 job refresh, extranonce, version rolling, share validation and block submission;
- isolated Bitcoin Core regtest round-trip integration harness.

A negative `prioritisetransaction` fee delta is a valid mining-policy input. It must not make cluster linearization or GBT construction fail; a non-negative mining floor simply excludes a chunk whose modified fee falls below that floor.
