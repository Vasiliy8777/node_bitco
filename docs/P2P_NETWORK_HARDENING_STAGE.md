# P2P / Network Hardening stage

This stage closes the production P2P/network-hardening milestone on top of the existing implementation.

## Covered mechanisms

- inbound/outbound peer lifecycle and handshake state machine
- outbound full-relay/block-relay-only roles and supervisor replenishment
- reconnect, netgroup/address exclusions and tried-collision feelers
- AddrMan persistence/selection and BIP155 ADDRv2
- ban/discouragement and protocol-violation disconnect paths
- peer liveness ping/pong and idle handling
- bounded asynchronous writes, write budgets and socket write deadlines
- transaction/inventory relay, WTXID relay, SENDHEADERS, FEEFILTER
- compact block negotiation/reconstruction
- BIP157 compact-filter serving
- BIP324 v2 negotiation, fallback, full-duplex transport and Core 31 interoperability
- header-sync peer failover under asynchronous disconnect races

## Closure fixes

1. A peer closed asynchronously between outbound admission and the first GETHEADERS is now treated as a network failover event instead of escalating a benign `IllegalStateException` to node-wide FAILED state.
2. BIP324 AUTO downgrade is observable in debug logs, while `getpeerinfo.v2_fallback` remains machine-readable.
3. The stage gate runs the complete reactor suite; the existing focused P2P tests remain the executable contract for the mechanisms above.

## Gate

Run:

    .\tools\p2p-network-hardening-stage-gate.ps1

The stage is closed only when the full Maven reactor is green and the script prints:

    P2P / NETWORK HARDENING STAGE: CLOSED
