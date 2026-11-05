# Future header rejection — Bitcoin Core 32.1

References:
[ContextualCheckBlockHeader](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp),
[MaybePunishNodeForBlock](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

Headers strictly more than two hours beyond the validation clock carry a
separate `TIME_FUTURE` rejection reason. The exact two-hour boundary remains
valid. The relay rejects these HEADERS and compact-block announcements without
disconnecting or discouraging the sender, for both inbound and outbound peers.
No successful batch receipt, new-block credit, compact reconstruction request or
full-block fallback is generated from the rejected header.

PoW and required difficulty are checked before future-time classification.
Otherwise invalid work could obtain the non-punishable timestamp reason.
The BIP23 proposal path still skips only PoW and checks the required difficulty.
MTP, BIP94 lower-time bounds and block-version checks remain enforced.

The compact-block header path now shares explicit header rejection handling:
cached-invalid duplicates are ignored without punishment through this path,
while invalid PoW/difficulty still reaches protocol-violation handling instead
of escaping as an unclassified runtime error.

Tests use a controlled clock, real regtest PoW and RocksDB. They cover both
connection directions and both announcement forms; a future header leaves no
stored index or permanent failure marker, and the exact same header is accepted
when the clock advances by one second to the two-hour boundary. Additional cases
check invalid work despite a future timestamp, cached-invalid compact blocks,
and reason precedence for incorrect difficulty. The focused run passed all 49
cases: `target/core-update-audit/core32-future-headers-focused.log`.

The full Bitcoin/RVN reactor then passed 5,119 cases: 5,107 executed, 12 opt-in
skips, zero failures/errors. Both applications packaged successfully, including
the native Bitcoin Core acceptance matrix:
`target/core-update-audit/core32-future-headers-full-build.log`.

Production verification on November 3 at approximately 15:34 Asia/Bangkok:
exactly one Bitcoin process, PID 92880, original IDEA `BitcoinNodeApplication GPU`
configuration using `app/target/classes`. State `RUNNING`; authenticated RPC
height/headers 974372, IBD false, ten connections including two block-relay peers.
CUDA logged accepted shares at approximately 2.59 GH/s. Evidence:
`target/core-update-audit/core32-future-headers-live-verification.json`.

Scope: this change covers asynchronous relay header rejection, including the
header carried by compact blocks. It does not establish all Core peer permission
exemptions or change synchronous header-sync failover/timeout policy. Full
historical replay and complete Core equivalence are not claimed.
