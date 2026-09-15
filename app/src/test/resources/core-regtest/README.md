# Independent Bitcoin Core regtest fixture

Generated locally with Bitcoin Core v31.1.0 on 2026-09-16 (local time), using a
fresh temporary datadir, -regtest -listen=0 -networkactive=0 and RPC on loopback.
Command: generatetodescriptor 3 raw(51).
blocks.txt pins the hashes; *.bin are getblock HASH 0 decoded from hex.
*.utxo.json are Core gettxout results for each coinbase output 0 after all three blocks.
chain.json records Core's final chain state. The instance was stopped via RPC.
BitcoinCoreRegtestBlocksTest imports these independently generated blocks and compares
the pinned tip, 50 BTC coinbase outputs and script 51, including a clean restart.
This is a limited cross-implementation test, not full UTXO-set comparison or reorg testing.
