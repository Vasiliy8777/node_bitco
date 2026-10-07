# CUDA solo miner

This client mines SHA-256d through the node's Stratum V1 interface. NVIDIA CUDA
Toolkit NVRTC compiles the kernel at runtime; Visual Studio and pip packages are
not required. Python 3.10+ and an NVIDIA driver are required. Set `CUDA_PATH` if
the toolkit is not installed at the default CUDA 12.2 location.

## Windows launch

### IntelliJ Spring Boot Play

Select **BitcoinNodeApplication GPU**, then click Play. This shared configuration
uses `mainnet,gpu`, module `app`, and the project root as its working directory.
Spring Boot launches the CUDA worker after application startup and owns its
shutdown; both node and worker output appear in the Run console. Stop the
Spring Boot application to stop both processes. The existing local
`BitcoinNodeApplication` configuration is also updated to `mainnet,gpu`.

The payout address comes from `BITCOIN_MINING_PAYOUT_ADDRESS`. If no
`BITCOIN_STRATUM_PASSWORD` is provided, Spring generates one and passes the same
value to Stratum and the worker's environment. No password is saved in the run
configuration. Set `BITCOIN_GPU_PYTHON` to the Python executable path if needed.
To run only the node/Stratum, add `--bitcoin.gpu-miner.enabled=false`.

### PowerShell launcher

Set `BITCOIN_MINING_PAYOUT_ADDRESS` in your environment. The launcher selects
mainnet for `bc1`, testnet4 for `tb1`, and regtest for `bcrt1`. For testnet3 use
`-Network testnet`. The Java address decoder checks checksum and network too.

```powershell
./tools/gpu-miner/Start-GpuMining.ps1
./tools/gpu-miner/Get-MiningStatus.ps1
./tools/gpu-miner/Stop-GpuMining.ps1
```

The PowerShell launcher disables the Spring-managed worker to avoid duplication,
builds an executable jar, tests the GPU against CPU SHA-256d, then
starts the node and miner in hidden processes. `-SkipBuild` uses the existing jar.
Logs and process IDs are under `target/gpu-mining`. Node data uses the selected
chain profile, currently `K:/BitcoinJavaNode`, with a separate directory per
network. Testnet data cannot provide a synchronized mainnet chain.

The default worker is `miner.gpu01`. `BITCOIN_STRATUM_PASSWORD` authenticates the
worker. If absent, the launcher creates a random password for its child
processes; it does not write it to disk. Set an explicit shared password before
launch when you want to connect other workers. Stratum binds to localhost:3333.
GPU profile uses difficulty 1 with variable difficulty down to 0.001. It must be
combined with a network profile (`mainnet,gpu`, for example).

The worker stays idle until the node is synchronized and has a current peer
view. On loss of readiness, the server disconnects miners and invalidates jobs;
the client reconnects and waits. GPU errors and authorization errors stop the
client. Each GPU solution is independently checked using CPU SHA-256d before
submission. Nonce ranges never wrap; extranonce2 advances after 2^32 nonces.

## Checks

```powershell
python -m unittest discover -s tools/gpu-miner -p test_miner.py
python tools/gpu-miner/miner.py --self-test
python tools/gpu-miner/miner.py --benchmark-seconds 5
./mvnw.cmd -pl app -am test '-Dtest=BitcoinCoreMiningRoundTripTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dbitcoin.core.binary=C:/Program Files/Bitcoin/daemon/bitcoind.exe' '-Dbitcoin.gpu.test=true'
```

Java regtest fixtures explicitly override the payout address, so an inherited
`BITCOIN_MINING_PAYOUT_ADDRESS` for mainnet does not affect these tests.
The opt-in Core test starts isolated regtest databases, synchronizes the Java
node over P2P, launches the actual CUDA client, verifies Core accepted the GPU
block, and verifies its reward script matches the requested wallet address.

## Later ASIC connection

ASICs use the same Stratum V1 service. Set an explicit password, choose a LAN
bind address and permit that address/port through your firewall, then configure
the ASIC URL `stratum+tcp://NODE_LAN_IP:3333`, worker `miner.asic01`, and password.
Use a separate ASIC configuration with an appropriate initial share difficulty
(the server default is 65536); GPU difficulty 1 creates excessive share traffic
for an ASIC. Do not expose RPC or Stratum directly to the Internet. Version
rolling is supported by the server. Physical ASIC compatibility remains to be
tested with the actual hardware.
