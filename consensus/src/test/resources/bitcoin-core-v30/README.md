# Bitcoin Core v30 Script vectors

`script-legacy.txt` contains all 1101 rows without an explicit witness array from
https://github.com/bitcoin/bitcoin/blob/v30.0/src/test/data/script_tests.json
The unmodified source is retained in script/src/test/resources/bitcoin-core-v30/script_tests.json.
Each row contains base64 UTF-8 scriptSig, scriptPubKey, flags and expected error,
separated by pipes. Order and expected results are unchanged; comments are omitted.
Upstream license: https://github.com/bitcoin/bitcoin/blob/v30.0/COPYING (MIT).
The test checks acceptance/rejection using InputScriptValidator, including implicit
empty witness, but does not compare precise ScriptError codes.
Explicit witness-array rows are not yet included.

The active projection is now `script-all.txt`: all 1212 data rows, including witness.
Fields: base64 UTF-8 scriptSig, scriptPubKey, flags, expected error, amount in satoshis,
witness item count, semicolon-separated witness items. Empty items are preserved.
Core's #SCRIPT#, #CONTROLBLOCK# and #TAPROOTOUTPUT# markers are expanded following
src/test/script_tests.cpp (single TapLeaf, internal key from private scalar 1).
Expected errors are unchanged; only acceptance/rejection is compared.

`tx_valid.json` and `tx_invalid.json` are unmodified Core v30.0 test data from
https://github.com/bitcoin/bitcoin/tree/v30.0/src/test/data (same MIT license).
Their .txt projections retain all 120 valid and 93 invalid data rows in order:
raw transaction | flags | prevout records. Each prevout is hash,index,amount,
base64 UTF-8 script. Missing amounts use zero, as in the Core harness.
Valid-case flags are EXCLUDED flags; invalid-case flags are REQUIRED flags.
BADTX cases must fail parsing or basic validation. The adapter checks the supplied
flag combination, not Core's randomized and individual-flag variation loops.
