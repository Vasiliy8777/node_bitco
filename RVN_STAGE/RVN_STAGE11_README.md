# RVN Stage 11 — wire format boundary and fail-closed batch preflight

Based on snapshot `node_bitco-main - 2026-10-23T205822.704.zip`.

**Added**
- `RvnHeaderFormatPolicy`: explicitly configured activation timestamp for the 80/120 byte header format.
- `RvnHeaderCandidateFactory`: derives all metadata from raw bytes except claimed block ID.
- `RvnHeaderBatchPreflight`: whole-batch structural continuity, uniqueness, height and format checks.
- `RvnStage11Test`: tests for activation boundary, preflight, and no partial mutation on structural errors.

**Changed**
- `RvnHeaderBatchAdmission`: new `admitPreflighted` overload. Original `admit` unchanged for compatibility.

IMPORTANT: The activation timestamp is caller-configured; choosing the correct network/chain parameters is required.
This stage is NOT a consensus verifier and does NOT compute X16R/X16RV2/KawPoW, block IDs,
expected difficulty, or timestamp consensus. All headers remain untrusted without a complete verifier.
Preflight protects from partial mutation on *structural* errors only; later proof failures
may still leave earlier validated headers admitted. No automatic synchronization is enabled.

Only RVN consensus sources/tests changed. Bitcoin, root POM, app config, GPU untouched.

Commands (no clean):
mvn -pl rvn-app -am test
mvn -pl rvn-app -am package -DskipTests
