# GETHEADERS with a null locator

Implemented 2026-11-02 against Bitcoin Core 32.1's
[`GETHEADERS` handler and `BlockRequestAllowed`](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

A GETHEADERS locator may contain zero hashes. In that case the peer requests
exactly the header identified by hashStop. The previous message constructor and
decoder rejected this valid wire form. They now accept it while retaining the
101-hash maximum, canonical CompactSize encoding, payload-length and trailing-byte
checks. The existing synchronous downloader still constructs ordinary locators.

The relay returns exactly one header for an eligible known hash. It silently
ignores unknown or ineligible hashStop values, rather than sending an empty
HEADERS or closing the connection. The existing minimum-chainwork guard runs
first and continues to send an empty HEADERS below that threshold. Successful
direct replies update the peer's last sent header just like ordinary replies.

Active-chain headers are eligible regardless of age. Outside the active chain,
the header must have script-validation status and no failed ancestry. Both the
timestamp age relative to the best header and the proof-equivalent age must be
strictly below 30 days. Equivalent time uses the best header's block proof and
the network target spacing, with integer division. The checks run under the
existing chain lock and do not scan the block index or load block bodies.

Tests exercise the zero-locator wire form and malformed encodings, exact age
boundaries, missing script validation, a formerly active validated tip following
reorganization, failed branches, unknown hashes, and an end-to-end relay worker
reply to a genesis request on a longer chain. The latter proves that unknown
requests do not generate an empty response and that the reply contains only
hashStop rather than its successors.

This phase added direct header request support. The subsequent
[side-block-serving phase](side-block-serving-core32.md) applies its eligibility
gate to GETDATA bodies as well. Neither phase adds privacy-network transports
or establishes complete Core equivalence. Complete historical mainnet replay and
low-work header presync remain outside demonstrated correspondence.

The original single `BitcoinNodeApplication GPU` IDE launch is retained. The
production node is stopped before compilation; no deployment JAR is introduced.

The 61-case focused reactor passed, including the native Core 32.1 acceptance
matrix (77 scenarios inside one JUnit case): `core32-direct-header-focused.log`.
The first full run (`core32-direct-header-full-build.log`) exposed concurrent
Mockito restubbing in the pre-existing relay recovery test. That fixture now
installs its answers before starting the worker and varies atomic state instead;
its assertions and production relay behavior are retained. The full repeat is
recorded in `core32-direct-header-full-confirmation.log`.
That repeat completed successfully: 5,081 total cases, 5,069 passed, 12 opt-in
skips and zero failures/errors. Both Bitcoin and RVN applications packaged.

Live verification at 20:22 Asia/Bangkok: the original IDE configuration launched
PID 80676, reached RUNNING and reused two saved anchors. Process enumeration
confirmed exactly one Bitcoin node. Authenticated RPC reported height/headers
974262, IBD false, eight connections including two block-relay peers. CUDA resumed
at approximately 2.58 GH/s with 13 accepted shares. Evidence:
`target/core-update-audit/core32-direct-header-live-verification.json`.
