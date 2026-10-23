# RVN stages 28–31 — fork-safe contextual recovery

Based on actual archive `node_bitco-main - 2026-10-23T213557.878.zip`.

28. Repair the Stage 24 contextual journal recovery bug: an index can hold forks,
    but a journal is not guaranteed to be a contiguous `headers` message.
29. Partition the parent-first journal into contiguous runs, each <= 2000,
    revalidating each run atomically against the staged index.
30. Reject duplicate claimed IDs in the journal; keep fail-closed behavior and
    never return a partially reconstructed index.
31. Add JUnit tests for fork recovery, best tip and common ancestor, staged
    context access, duplicate ID rejection, and corrupted journal rejection.

NOTE: This is a correctness patch, not a real X16R/X16RV2/KawPoW implementation.
Production must provide genuine PoW, ID and difficulty verification.
Existing tests' permissive verifier is TEST ONLY.

Run `mvn -pl rvn-app -am test` (without clean).
