# Core v31.1.0 mature coinbase spend

Generated in the isolated offline regtest instance described in ../core-regtest/README.md.
Blocks 1–101 pay raw(51); block 102 includes a spend of block 1 coinbase output 0.
Core required -acceptnonstdtxn=1 for the OP_TRUE input (a policy setting, not a
consensus bypass). The spend creates a P2WSH(OP_TRUE) output of 4,999,990,000 satoshis;
fee is 10,000 satoshis. spend-utxo.json records Core gettxout after confirmation.
blocks.txt pins all 102 block hashes, spend.txt pins spent and spending txids.
The test checks connection, removal/creation of coins, coinbase fees and clean restart.
No independent full UTXO-set hash, reorg or abrupt crash is tested here.
Core was stopped with RPC stop after collection.
