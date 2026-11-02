# Bitcoin Core v32.1 consensus vectors

The JSON files are unmodified data from
https://github.com/bitcoin/bitcoin/tree/v32.1/src/test/data.
`SHA256SUMS` records their byte hashes. Bitcoin Core's MIT license applies.

Regenerate with `python consensus/reference/update-core-vectors.py`, or supply
`--source-directory target/core-update-audit` to use downloaded `core32-*.json`.
The projection script preserves every expected result and data-row order.
Normal Maven tests are offline and do not need Python or Core.

- `script-all.txt`: all 1237 Script rows, including witness and the upstream
  Taproot markers. Seven base64 UTF-8 fields contain scriptSig, scriptPubKey,
  flags, expected error, satoshi amount, witness item count and witness items.
- `tx_valid.txt`: all 121 valid transaction rows.
- `tx_invalid.txt`: all 93 invalid transaction rows.

Transaction projections use `raw transaction|flags|prevout|...`; each prevout is
`hash,index,amount,base64 script`. Missing amounts are zero, as in Core's harness.
Valid-case flags are excluded flags; invalid-case flags are required flags.
The adapter also tests each individual flag change and 64 reproducible mixed
flag combinations, including maximal excluded/minimal required invariants.
BADTX rows must fail parsing or basic transaction validation.

Tests pin normalized projection hashes and compare acceptance/rejection, rather
than exact ScriptError text. The 500 sighash vectors are byte-identical between
the existing v30 corpus and v32.1 and remain covered in the script module.
