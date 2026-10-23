# Genesis fixture

`mainnet-genesis.hex` reconstructs the exact genesis transaction and header from
RavenProject/Ravencoin commit `6d48ae0175b10283248146ae3080e2ba70966739`,
`src/chainparams.cpp` CreateGenesisBlock / CMainParams.

The fixture's transaction hash matches the independently published genesis
Merkle root `28ff00a867739a352523808d301f504bc4547699398d70faf2266a8bae5f3516`.
The genesis coinbase is never inserted into a spendable UTXO set.
