# ADDR permission and GETADDR privacy cache

Reference: Bitcoin Core 32.1 [`CConnman::GetAddresses` and inbound network key](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net.cpp),
[`ProcessMessage` address processing](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp),
and [permission definitions](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_permissions.h).

The explicit inbound numeric-CIDR policy now accepts `addr`. This right bypasses
the shared ADDR/ADDRV2 processing token bucket and obtains a fresh GETADDR sample.
It does not imply NoBan, Download, manual connection status or any transaction
permission. VERSION service bits cannot grant it. `getpeerinfo.permissions`
reports the locally granted right. No production permission rules were enabled.

Ordinary GETADDR replies share a 21-hour plus uniform [0, 6-hour) cache. Like
Core's inbound network key, the key includes remote network family and the local
bind IP **and port**, excluding the remote IP and ephemeral port. The existing
transport currently supplies IPv4/IPv6 ingress; onion/I2P ingress separation
remains part of the additional-network workstream. Refresh occurs strictly after
expiry. Empty responses are cached. AddrMan mutations cannot update a cached
address, services or timestamp in place. Fresh selection excludes banned and
discouraged IPs; cached snapshots are not re-filtered on later requests.

An ADDR-authorized request obtains a fresh sample without replacing an ordinary
cache. Both paths retain inbound-only GETADDR, one response per connection,
message decoding/size limits and block-relay-only exclusions.

Checks: deterministic clock tests cover strict expiry, monotonic clock overflow,
cache key separation, empty samples, immutable timestamp/service snapshots and
permission bypass without ordinary-cache replacement. Socket-backed ADDR/ADDRV2
tests verify that only ADDR bypasses the shared bucket, with ordinary, NoBan and
Download negative cases. Socket-backed GETADDR checks retain the inbound and
one-request guards for ADDR-authorized peers.

This implements the ADDR part of direction 3. BloomFilter, Relay, ForceRelay and
Mempool permissions remain open; it does not close the full peer-permissions
workstream or establish complete Core equivalence.

Validation on 2026-11-05: all 5,207 full-reactor cases completed (5,195 passed,
12 opt-in skipped, zero failures/errors), including the native Core 32.1
compatibility test. Both applications packaged; log:
`target/core-update-audit/core32-addr-full-build.log` (20:05:00 +07:00).
The original `BitcoinNodeApplication GPU` configuration restarted from
`app/target/classes`, mainnet/gpu, as PID 128836. RUNNING was observed at
20:05:48; RPC height/headers 974669/974669, IBD=false and a restored mempool
of 25,557 transactions were verified with exactly one Bitcoin process.
The final 20:06 check advanced to height/headers 974672/974672, IBD=false,
eight connections (two block relay), 22,271 mempool entries and accepted CUDA
shares at approximately 2.25 GH/s. There were no locally privileged peers and
exactly one Bitcoin process. Evidence:
`target/core-update-audit/core32-addr-live-verification.json` and PID-specific
entries in `target/mainnet-node.log`.
