# RVN stages 40–43: bounded peer behavior and version negotiation

Snapshot: `node_bitco-main - 2026-10-23T220134.544.zip`.

40. Handshake now has an overall monotonic deadline, not up to 16 independent full-length read timeouts.
41. Restore the intended socket read timeout after successful handshake.
42. `getheaders` request version is capped to the negotiated remote version.
43. Limit ignored unrelated inbound messages to 64 per request; added loopback peer regression tests.

These are transport hardening changes, not Ravencoin consensus validation.
No BTC modules, root POM, mining or app configuration changes.

Run: `mvn -pl rvn-app -am test`
