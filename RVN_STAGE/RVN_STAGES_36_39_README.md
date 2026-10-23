# RVN Stages 36–39: consistent active-chain snapshot and header pagination

Source: `node_bitco-main - 2026-10-23T215455.041.zip`.

36. Fix a TOCTOU race in `RvnVerifiedHeadersResponder`: previously `bestId()`,
    `pathToAnchor()` and `verifiedRaw()` were individually synchronized but not
    one atomic snapshot. A concurrent tip update could change the response.
37. Add `RvnValidatedHeaderIndex.activeHeadersSnapshot()` which atomically
    captures the active path and exact verified bytes under the index monitor.
    Snapshot entries defensively copy bytes on construction and access.
38. Add `RvnVerifiedHeadersPage.request()` for an outbound locator-based
    `getheaders` request built from a VERIFIED local tip, and `mayHaveMore()`
    to recognize full 2000-header responses. Returned remote headers are NOT
    marked verified by this utility.
39. Regression tests for snapshot immutability, side-fork exclusion, response
    consistency and request/page behavior.

This patch does NOT implement Ravencoin PoW (X16R/X16RV2/KawPoW), real
retargeting or production P2P wiring. Do not use permissive test verifiers
in production. Only rvn-consensus source/tests are modified.

Run: `mvn -pl rvn-app -am test`.
