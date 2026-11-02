# Core update audit — 2026-11-01

This repository contains independent Java implementations, not installed Bitcoin
or Ravencoin Core binaries. Updating applicable behavior does not change the
Java node's identity or establish full compatibility with a new Core release.

## Upstream references

- Bitcoin latest stable: [v32.1](https://github.com/bitcoin/bitcoin/releases/tag/v32.1),
  published 2026-10-28. Reviewed the
  [32.1 notes](https://github.com/bitcoin/bitcoin/blob/master/doc/release-notes/release-notes-32.1.md)
  and [31.2 maintenance notes](https://github.com/bitcoin/bitcoin/blob/master/doc/release-notes/release-notes-31.2.md),
  with source checks against the v32.1 tag.
- Ravencoin latest stable: [v4.8.0](https://github.com/RavenProject/Ravencoin/releases/tag/v4.8.0),
  published 2026-08-19, commit `22549129888d02e0e08fcdb9f96f3c699167e774`.
  Compared release source with the older pinned reference
  `6d48ae0175b10283248146ae3080e2ba70966739`. Ravencoin master is divergent
  from the release; its branch name must not be treated as a newer stable release.

## Changes applied

| Upstream change | Java implementation |
| --- | --- |
| #36199: reject RFC 9637 IPv6 documentation prefix `3fff::/20` | Filter both address gossip formats and DNS results; keep these addresses out of AddrMan and remove old entries and their bucket references when restoring a snapshot. Tests cover both ends of the /20 and adjacent valid prefixes. |
| #35691: remove retired testnet3 DNS seed | Removed `seed.testnet.bitcoin.sprovoost.nl`; mainnet, testnet4 and signet seed sets already match the release. |
| #35766: assume v2 transport support for DNS seed addresses | DNS results now carry `NODE_P2P_V2`. Existing AUTO transport already attempts v2 and supports v1 fallback; no invented NODE_NETWORK or NODE_WITNESS capability is added. |
| #34911: omit obsolete `getmempoolinfo.fullrbf` by default | Removed the field and updated the RPC integration expectation. This node has no deprecatedrpc compatibility setting. Full RBF policy itself is unchanged. |

Source checks:
[netaddress.cpp](https://github.com/bitcoin/bitcoin/blob/v32.1/src/netaddress.cpp),
[chainparams.cpp](https://github.com/bitcoin/bitcoin/blob/v32.1/src/kernel/chainparams.cpp),
[net_processing.cpp](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

## RVN fixes already present

- Independent KawPoW mix computation and comparison in `RvnKawpowProof`,
  `RvnPowRouter` and the GPU header verifier; supplied mix bytes are not trusted.
- Declared KawPoW header height checked against branch height on CPU/GPU paths.
- Recovery checkpoint at 4,487,775, including stored best-chain ancestry checks.
- Bit 11 asset-transfer activation, input/output amount bounds and bounded
  running totals. Legacy arithmetic remains conditional on deployment state.
- Existing tests exercise Core hash vectors, invalid proofs, checkpoints,
  deployment transitions and asset amount overflow.

`RvnKawpowDagBatch` is a Java retrieval helper with no direct Core counterpart.
Its ordering, duplicate handling and failure propagation tests pass; no upstream
consensus change requires an edit to this helper.

RVN wallet RPC fixes for `sendmany`/`sendtoaddress` do not apply to this node,
which has no corresponding wallet implementation. Existing historical replay
limitations remain documented in `rvn-chain/reference/HISTORICAL_VALIDATION.md`.

## Remaining compatibility differences

The subsequent Core 32.1 work implements global relay budgets, both fee estimators,
OpenRPC/named arguments, parallel prevout reads, inbound budgets, RPC connection
limits and updated mining defaults. See [the current compatibility matrix](bitcoin-core32-compatibility.md)
for exact behavior, independent Core 32.1 validation and remaining differences.
This still does not establish complete Bitcoin Core feature parity.

BIP323 warning changes do not require a patch to a nonexistent unknown-version
warning subsystem. Historical deployment machinery is retained. Existing
`vsize_adjusted`/`vsize_bip141` RPC fields already cover part of #32800.
Wallet/GUI/build-system and Tor private-broadcast fixes concern components this
Java node does not implement; no C++ changes were copied into unrelated Java code.

## Validation

- Maven 3.9.6 / JDK 21: 406 selected tests passed across the Bitcoin and RVN
  reactor, including KawPoW Core vectors and `RvnKawpowDagBatchTest`.
- Final affected-test/package run: 67 tests passed, including outbound
  connection management and the DNS documentation-prefix regression. Both
  executable application JARs were packaged successfully.
- Raw test/build logs: `target/core-update-audit/` (ignored build artifacts).
- The Maven wrapper's newer resources plugin hit Windows file-sharing failures
  while copying existing fixtures. The installed Maven 3.9.6/resources 3.3.1
  completed the selected test run without that failure.

Only the Bitcoin mainnet/GPU application was running when work started. RVN's
saved launcher record refers to an old process, not an active RVN node.
Bitcoin PID 55188 stopped through its Spring Admin JMX shutdown operation;
the log confirms `RUNNING -> STOPPING -> STOPPED` and CUDA worker termination.
The same IDE configuration `BitcoinNodeApplication GPU` was launched again as
PID 56764 with `mainnet,gpu` and the existing chainstate path.
At 13:14:36 Asia/Bangkok it reached `RUNNING`. Logs confirm P2P listening on
8333, successful outbound handshakes and CUDA worker PID 55496 on the RTX 4070,
authorized to Stratum. Startup took 144 seconds. RVN was built and tested but
not started because there was no active RVN process to restart.
