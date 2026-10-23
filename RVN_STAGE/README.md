# Ravencoin node integration

Current implementation, run instructions, validation evidence and remaining work:
[IMPLEMENTATION_STATUS.md](IMPLEMENTATION_STATUS.md).

The current code includes independent Java X16R/X16RV2/KawPoW engines and opt-in
mainnet header synchronization and optional commitment-checked body download
backed by an independent RocksDB store. Mining payout uses `RVN_MINING_ADDRESS`.
It does not yet validate full blocks/UTXO/assets or perform GPU mining.

IntelliJ Spring Boot configurations:

- `BitcoinNodeApplication GPU`: existing Bitcoin launcher.
- `RavencoinNodeApplication (development)`: offline RVN engine checks.
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
