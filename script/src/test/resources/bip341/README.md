Canonical wallet vectors:
https://github.com/bitcoin/bips/blob/master/bip-0341/wallet-test-vectors.json

key-path-vectors.txt is a lossless projection of keyPathSpending/inputSpending used
by the Java tests without a JSON dependency. Fields separated by |:
rawUnsignedTx, amount:scriptPubKey entries separated by ;, txinIndex, hashType,
intermediary.sigHash, expected.witness[0].

Source specification: https://github.com/bitcoin/bips/blob/master/bip-0341.mediawiki
License: BSD-3-Clause (BIP341).
