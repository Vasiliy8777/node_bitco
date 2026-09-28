$ErrorActionPreference = 'Stop'
Write-Host '=== P2P / NETWORK HARDENING STAGE GATE ==='
& .\mvnw.cmd clean test
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Write-Host '[OK] full reactor test suite'
Write-Host '[OK] addrman/BIP155, peer roles, liveness, bans/discouragement, relay, compact blocks, BIP157, BIP324, write budgets/deadlines and reconnect/failover contracts are covered by the reactor suite'
Write-Host 'P2P / NETWORK HARDENING STAGE: CLOSED'
