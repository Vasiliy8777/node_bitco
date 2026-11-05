# Peer access and manual connections — Core 32.1 workstream

References: [permission inheritance](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_permissions.h),
[misbehavior exemptions](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

This batch adds immutable locally assigned connection access, separate from
untrusted VERSION service bits. Inbound rules are explicit numeric IP/CIDR
rules; hostnames and unsupported/implicit permissions fail configuration parsing.
The default empty rule set grants no permissions. NOBAN includes DOWNLOAD in the
permission set, matching Core's flag inheritance. Exemptions cover peer-level
misbehavior, address-ban admission, inbound eviction and the resource rules below.
RPC `getpeerinfo.permissions` reports the locally assigned set.

Example local configuration syntax (not enabled by this change):

```yaml
bitcoin:
  p2p:
    inbound-permissions: "noban@192.0.2.8/32;download@2001:db8::/32"
    manual-peers: "192.0.2.9:8333,[2001:db8::9]:8333"
```

Manual peers use a distinct connection role and immutable manual marker. They
relay transactions/addresses but do not consume automatic outbound slots or the
four chain-sync protection slots. Their dedicated bounded manager supports up
to eight endpoints, retries closed/failed connections every five seconds, and
owns an independent connector so cancellation does not affect automatic peers.
Explicit close/shutdown and transport failure still close a protected peer.
No manual endpoints are added to the running user's configuration.

Protocol-close bookkeeping now distinguishes local addresses: loopback and IPv4
0/8 connections can be closed for misbehavior without discouraging their shared
address. Nonlocal addresses still get ordinary discouragement; configured NoBan
and manual markers prevent it. VERSION cannot set either marker.

## Approved resource exemptions

The user explicitly approved these resource exceptions on November 5 after the
initial automatic review rejection. The following behavior is now implemented:

1. Only a connection matched by an explicit numeric `noban@CIDR` rule bypasses
   low-work header presynchronization after PoW/continuity and parent checks.
   Contextual header validation, chain failure state and block validation remain
   mandatory.
2. That connection may request older locally available eligible blocks beyond
   the NODE_NETWORK_LIMITED 290-block window. This does not create/prune-restore
   data or make invalid/unvalidated side-chain bodies eligible.
3. Locally granted DOWNLOAD (including inherited DOWNLOAD for NoBan) permits
   GETHEADERS serving below local minimum chainwork, following Core's rule.
4. Ordinary unconfigured connections retain every existing guard. The change
   does not automatically grant privileges to localhost or any other address.

MANUAL alone grants neither exception. Compact-block minimum-work checks remain
mandatory, matching Core's separate compact admission path. Full Core permission
parity is not claimed. Bloom,
Relay/ForceRelay, Mempool and Addr permission-specific features are outside this
batch. The broader synchronous failover policy requires further comparison;
manual-only initial bootstrap was added and tested in the subsequent
[bootstrap/PRESYNC RPC batch](manual-bootstrap-presync-rpc-core32.md).
The reconnect manager is available to
normal running-node relay/live synchronization. The subsequent
[live header-timeout fix](live-header-timeout-core32.md) retains NoBan connections
on a header response timeout while trying another source; ordinary timing and
initial timeout policy still require comparison.

Focused tests cover immutable numeric-CIDR rules and inheritance, privilege
separation, real handshake with arbitrary remote service bits, ordinary versus
NoBan/manual misbehavior handling, local/nonlocal address discouragement,
manual reconnect/close, role accounting, configuration, RPC and existing header
and block-serving guards. Log: `target/core-update-audit/core32-peer-access-focused.log`.

The initial focused run passed 73 cases. The full Bitcoin/RVN reactor then
passed 5,163 cases: 5,151 executed, 12 opt-in skips, zero failures/errors
(`target/core-update-audit/core32-peer-access-full-build.log`).
After adding the late-registration shutdown guard and inbound-capacity case,
the final package rebuild passed all 75 selected cases with zero skips/failures/
errors (`target/core-update-audit/core32-peer-access-final.log`). Both applications
packaged successfully. That final rebuild covers the final manual-close change;
the full-reactor count refers to the earlier complete run.

The explicitly approved resource-exemptions batch passed all 42 focused cases,
including real database header admission with invalid-PoW/unknown-parent/contextual
rejection, Download-only separation, low-work GETHEADERS and all old-body request
forms with missing-data handling. Logs:
`target/core-update-audit/core32-permission-exemptions-focused.log`.
The final full Bitcoin/RVN package run completed November 5 at 01:00:36 Bangkok:
5,168 total cases, 5,156 passed, 12 opt-in skips, zero failures/errors. It includes
the manual shutdown race guard and native Core 32.1 comparisons. Log:
`target/core-update-audit/core32-permission-exemptions-full-build.log`.

Live verification on November 5 at approximately 00:36 Asia/Bangkok: exactly one
Bitcoin process, PID 105152, original IDEA GPU configuration, state RUNNING,
height/headers 974554, IBD false, five connections including two block-relay
peers. RPC showed zero peers with locally assigned permissions; no manual peer
endpoints or trust rules were enabled in the production configuration. CUDA
logged accepted shares at approximately 2.57 GH/s. Evidence:
`target/core-update-audit/core32-peer-access-live-verification.json`.

After the resource-exemptions full build, the original IDEA GPU configuration
started exactly one Bitcoin process, PID 118444. At approximately 01:03 Bangkok
on November 5 it was RUNNING, height/headers 974559, IBD false, seven connections
including two block-relay peers. Two anchors loaded. RPC still showed zero
privileged peers. CUDA logged accepted shares at approximately 2.51 GH/s.
Evidence: `target/core-update-audit/core32-permission-exemptions-live-verification.json`.
