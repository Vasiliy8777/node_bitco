# RPC / API operational completeness stage

This stage closes the node's supported non-wallet operational JSON-RPC surface.

## Transport and security

- loopback-only authenticated HTTP endpoint
- bounded request body and bounded worker/long-poll concurrency
- legacy JSON-RPC response compatibility
- JSON-RPC 2.0 response shape
- JSON-RPC batch requests
- JSON-RPC 2.0 notifications with HTTP 204
- per-element batch errors and notification suppression

## Operational methods

The stage covers the implemented chain, mempool, mining, network, index,
pruning and AssumeUTXO RPC families and adds the operational control/introspection
methods `help`, `getrpcinfo`, `ping`, and `savemempool`.

`savemempool` flushes the node's native RocksDB-backed durable mempool and reports
`rocksdb:mempool` as its storage identifier; this node does not create Bitcoin
Core's `mempool.dat` compatibility file.

`ping` immediately requests a BIP31 ping from each READY managed peer and leaves
RTT reporting to `getpeerinfo`.

## Deliberate non-claims

This is a non-wallet Java node, not a clone of every optional Bitcoin Core RPC
subsystem. RPCs requiring facilities the node does not implement (wallet,
ZeroMQ, REST, external byte-accounting/upload-target state, manual addnode state)
are not fabricated. In particular no fake `getnettotals` counters are exposed.

The stage is closed when the full reactor suite and the stage contract gate pass.
