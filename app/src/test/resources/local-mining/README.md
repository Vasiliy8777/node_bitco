# Locally mined block accepted by Core

MinedBlockAdmissionTest constructs a regtest height-1 block using our template,
coinbase and nonce builders, accepts it through NodeValidationService/BlockProcessor,
and verifies coinbase storage and clean restart. It exports target/locally-mined-regtest.hex.
On 2026-09-16 this exact artifact was submitted to a fresh isolated Bitcoin Core
v31.1.0 regtest (-listen=0 -networkactive=0). submitblock returned success (null),
and getbestblockhash matched 60a080333886210d0624aba0063667d0c1fcd295ab5c0c740757dbafb049525a.
accepted-block.hex and core-chain.json preserve the submitted bytes and RPC evidence.
Core was stopped via RPC. This external submission is a recorded manual integration
run, not automatically rerun by Maven. The candidate has no non-coinbase transactions.
