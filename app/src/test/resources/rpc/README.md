# RPC oracle vectors

`core32-psbt.json` is copied from Bitcoin Core v32.1:
https://github.com/bitcoin/bitcoin/blob/v32.1/test/functional/data/rpc_psbt.json
It is covered by Bitcoin Core's MIT license, retained in
`app/src/main/resources/rpc/CORE-LICENSE.txt`.

`bip370.json` contains the unique base64 examples from BIP370 by Ava Chow:
https://github.com/bitcoin/bips/blob/master/bip-0370.mediawiki
The BIP specifies BSD-2-Clause; see `bip370-LICENSE.txt`.

These public vectors contain synthetic keys and transactions. They are never
read from, imported into, or broadcast by the production node.

`bip327-sig-agg.json` contains the official BIP327 signature aggregation vectors:
https://github.com/bitcoin/bips/blob/master/bip-0327/vectors/sig_agg_vectors.json
The additional `aggregate_pubkey` values were generated with the BIP327 Python
reference `key_agg`, independently of the Java implementation.
BSD-3-Clause; see `bip327-LICENSE.txt`.

`musig-finalizer.json` contains 72 synthetic PSBTs generated with the BIP327
Python reference implementation, finalized and extracted by Bitcoin Core 32.1.
It covers raw/key/script paths, BIP328 derivation paths, Taproot Merkle tweaks,
three sighash types and PSBT 0/2. Signing keys are the public test scalars 1/2.
The fixture was generated independently of all Java signing/aggregation code.
No production wallet or chain data is included. Generator audit artifact:
`target/core-update-audit/generate-musig-finalizer-vectors.py`.

`core32-descriptors.txt` contains descriptor string literals extracted from
Bitcoin Core v32.1 `src/test/descriptor_tests.cpp` (valid and malformed cases).
The native RPC independently determines the expected result/error for each.
Core MIT license: `app/src/main/resources/rpc/CORE-LICENSE.txt`.

`core32-key-io-valid.json` and `core32-key-io-invalid.json` are Bitcoin Core
v32.1 key/address test vectors from `src/test/data/`, under Core MIT license.
They contain synthetic public fixtures, not production wallet data.
