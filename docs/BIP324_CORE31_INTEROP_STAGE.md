# BIP324 / Bitcoin Core 31 interoperability stage

This stage closes only when all three gates pass:

1. `mvnw.cmd clean test`
2. the complete official BIP324 v1.0.2 CSV corpus from `bitcoin/bips` passes `Bip324OfficialVectorsTest`
3. live bidirectional BIP324 interoperability with Bitcoin Core 31.x passes, including matching `session_id` for Java->Core and forced Core->Java v2 connections.

The vector fetcher intentionally downloads the canonical upstream corpus at certification time instead of silently vendoring a stale copy. The stage gate fails if the corpus is unexpectedly short.

Production hardening in this stage also closes accepted sockets on wrong-network v1 discrimination failures and reports `P2P_V2` in `getnetworkinfo.localservicesnames`.
