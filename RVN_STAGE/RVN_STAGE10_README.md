# RVN Stage 10 — verified-header index hardening and branch planning

Based on actual snapshot `node_bitco-main - 2026-10-23T204154.177.zip`.

Changed:
- RvnValidatedHeaderIndex.java: preserve exact verified wire bytes, reject duplicate
  block ID with changed wire bytes, expose defensive-copy `verifiedRaw`.

New:
- RvnChainTransition.java: compute disconnect/connect lists and common ancestor
  for already-verified competing branches. Does NOT execute UTXO reorg.
- RvnHeaderLocatorPlan.java: generate exponentially spaced getheaders locators
  using only validated index IDs and trusted anchor.
- RvnHeaderBatchAdmission.java: bounded sequential admission; explicitly
  partial progress on failure (not an atomic transaction).
- RvnStage10Test.java: branch, locator, duplicate mutation and fail-closed tests.

IMPORTANT: A full Ravencoin consensus verifier is STILL MISSING.
The TEST_ONLY verifier in JUnit is NOT suitable for production.
No automatic sync or mining is enabled. Do not use index results with
a fake verifier or untrusted anchor.

Only rvn-consensus sources/tests; no Bitcoin files, root POM or configs changed.
Run:
mvn -pl rvn-app -am test
mvn -pl rvn-app -am package -DskipTests

Main Java compilation was checked with JDK 21. JUnit/Maven not executed here.
