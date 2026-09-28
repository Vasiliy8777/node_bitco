# Bitcoin Core differential + real-network certification

This is a certification stage, not a second implementation of consensus/network/mining logic.

## Core31 / regtest certificate

`tools/core31-full-certify.ps1` requires running Java and Bitcoin Core 31.x regtest endpoints plus the Core binary path. It runs the full Maven reactor, the existing live consensus differential (state, UTXO, raw blocks, malformed mutations and reorg restore), bidirectional BIP324 interoperability, and the isolated Core mining/transaction-relay/reconnect/Stratum round-trip test.

## Public-network certificate

`tools/real-network-core-differential.ps1` is deliberately read-only. Use two independently synchronized nodes on the same non-regtest network: this Java node and Bitcoin Core. Supported chain labels are `main`, `test`, `testnet4`, and `signet` as exposed by `getblockchaininfo`.

The script waits until both nodes are out of IBD and have the same active/header tip, requires live peers, compares chainwork, and byte-compares raw headers and raw blocks at genesis, two historical checkpoints and the current tip. It never submits blocks/transactions or invalidates public-network blocks.

## Closing the stage

Run `core-real-network-certification-stage-gate.ps1 -Mode All ...`. The stage is closed only when both Core31/regtest and public-network certificates pass. `-Mode Core31` and `-Mode RealNetwork` exist so the two environments can be certified separately when they cannot run simultaneously.
