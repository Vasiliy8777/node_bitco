# Bitcoin Core v30.0 sighash vectors

`sighash.json` is the unmodified upstream test data:
https://github.com/bitcoin/bitcoin/blob/v30.0/src/test/data/sighash.json

Bitcoin Core is distributed under the MIT license:
https://github.com/bitcoin/bitcoin/blob/v30.0/COPYING

`sighash-vectors.txt` contains all 500 data rows, projected without changing values:
`raw transaction | scriptCode | input index | signed hash type | expected display hash`.
The projection avoids adding a JSON runtime dependency to the script module.
`BitcoinCoreSighashVectorsTest` validates every row and asserts the corpus size.

`script_tests.json` is the unmodified Core v30.0 corpus:
https://github.com/bitcoin/bitcoin/blob/v30.0/src/test/data/script_tests.json
It is covered by the same upstream MIT license.
`script-first-100.txt` projects the first 100 non-witness data rows, in upstream
order, to four base64 UTF-8 fields separated by pipes (scriptSig, scriptPubKey,
flags, expected error). Comments are omitted; expected outcomes are unchanged.
BitcoinCoreScriptVectorsTest checks acceptance/rejection, not exact error codes.
The remaining corpus, witness rows and transaction vectors are not yet executed.

The active Script test has now moved to the consensus module and executes all
1101 rows without explicit witness arrays. See consensus/src/test/resources/bitcoin-core-v30/README.md.
The first-100 projection is retained as the earlier subset; it is not a separate active test.
