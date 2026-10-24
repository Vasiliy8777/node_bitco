# Ravencoin node integration

Current implementation, run instructions, validation evidence and remaining work:
[IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

Memory/storage analysis and the current C: NVMe / K: HDD layout:
[STORAGE_AND_MEMORY.md](STORAGE_AND_MEMORY.md).

The current code includes independent Java X16R/X16RV2/KawPoW engines and opt-in
mainnet header synchronization and optional commitment-checked body download
backed by an independent RocksDB store. Mining payout uses `RVN_MINING_ADDRESS`.
Historical native checks, atomic coin/asset storage and a canonical root/sub/unique/channel
block checker are implemented, but full historical asset admission is incomplete.
The separate CUDA KawPoW worker passes real GPU Core-vector checks; production
mining jobs and candidate relay are not yet connected.

IntelliJ Spring Boot configurations:

- `BitcoinNodeApplication GPU`: existing Bitcoin launcher.
- `RavencoinNodeApplication (development)`: offline RVN engine checks.
- `RavencoinNodeApplication (CUDA Check)`: GPU DAG generation, four Core vectors
  and bounded nonce searches; no network mining. [Details](../rvn-pow/native/cuda/README.md).
- `RavencoinNodeApplication (mainnet headers)`: persistent RVN header sync.
- `RavencoinNodeApplication (mainnet block download)`: headers and candidate bodies.
- `RavencoinNodeApplication (historical native replay)`: persistent native coin
  checks of downloaded bodies, stopping before asset activation; mining stays off.

Build and test without `clean`:

```powershell
mvn -pl rvn-app -am package
```

The numbered stage READMEs and other patch notes describe historical snapshots;
their statements about missing components are not the current status. Keep them
as implementation history and consult `IMPLEMENTATION_STATUS.md` first.
