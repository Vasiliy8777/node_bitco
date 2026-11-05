# Offline Core 32.1 PRESYNC/REDOWNLOAD review

This model is **not integrated into the node**, its classpath, or its Maven
modules. It has no sockets, RPC, storage writes or access to production data.
After explicit user approval, it was adapted into the production
`app/.../HeadersPresyncState.java`; production integration, independent native
wire comparison and current verification are documented in
[the implementation report](../../docs/headers-presync-core32.md).

Algorithm reference: [Bitcoin Core v32.1 headerssync.cpp](https://github.com/bitcoin/bitcoin/blob/v32.1/src/headerssync.cpp),
[network parameters](https://github.com/bitcoin/bitcoin/blob/v32.1/src/kernel/chainparams.cpp),
[difficulty-transition bounds](https://github.com/bitcoin/bitcoin/blob/v32.1/src/pow.cpp).
Adapted algorithm copyright Bitcoin Core developers, MIT; the repository's Core
MIT notice is under `mempool/src/main/resources/META-INF/licenses/bitcoin-core-MIT.txt`.

The model replaces the earlier unused helper's full-segment SHA-256 commitments
with Core's salted single-bit commitments, randomized offset, two-phase state
machine, time-derived commitment bound and network-specific lookahead. Buffered
headers use 48 serialized bytes excluding the parent hash; Java array/deque
overhead still differs from C++ and is not a byte-for-byte heap equivalence.
Whole-message PoW/continuity checking is a caller precondition, as in Core.

The standalone check mines real regtest PoW and covers seven scenarios with 69
assertions, including a streamed 20,000-header chain and buffer release/order.
These are directed Java checks against documented upstream behavior, **not** a
native Core differential test or proof of full network integration.

Run from the project root in PowerShell, without Maven or modifying the running
node's classes:

```powershell
$reviewClasses = 'target/core-update-audit/presync-review-classes'
New-Item -ItemType Directory -Force -Path $reviewClasses | Out-Null
$reviewClasspath = 'common/target/classes;crypto/target/classes;protocol/target/classes;chain/target/classes;consensus/target/classes'
& 'C:/Program Files/Java/jdk-21/bin/javac.exe' -cp $reviewClasspath -d $reviewClasses tools/core32-presync-review/Core32HeadersPresyncModel.java tools/core32-presync-review/HeadersPresyncModelCheck.java
if ($LASTEXITCODE -ne 0) { throw 'Offline model compilation failed' }
& 'C:/Program Files/Java/jdk-21/bin/java.exe' -cp "$reviewClasspath;$reviewClasses" ru.bitcoin.node.review.HeadersPresyncModelCheck
if ($LASTEXITCODE -ne 0) { throw 'Offline model checks failed' }
```

Recorded result:
`target/core-update-audit/core32-presync-review-checks.log`:
`{"scenarios":7,"assertions":69,"failures":0,"productionIntegration":false}`.

Automatic approval review rejected the initial proposed production replacement
as complex untested critical-path logic that could disrupt chain acceptance or
availability. That rejected replacement was not applied. The temporary removal
of the unused helper was reverted to its exact previous source. This offline
model was the safer review alternative. The user subsequently gave explicit
integration confirmation; the approved implementation now has separate
production-state, real-network and native Core tests. The historical offline
checks alone should not be used as evidence of the current network integration.
