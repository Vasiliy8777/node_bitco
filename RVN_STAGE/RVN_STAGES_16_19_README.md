# RVN combined stages 16–19: contextual validation, verifier composition, audit and recovery

Based on `node_bitco-main - 2026-10-23T211752.214.zip`.

16. `RvnHeaderTimeRules`: 11-ancestor median-time-past and future-time window.
17. `RvnHeaderContextPolicy`: checks expected nBits (CALLER-SUPPLIED), MTP and future drift.
18. `RvnHeaderProofVerifierAdapter`: composes independent ID, PoW and context
    validators into the existing `RvnHeaderProofGate.Verifier`.
19. `RvnVerifiedChainAudit`: independently recomputes index heights and cumulative
    claimed work, verifies best-tip selection and parent-first journal order;
    `RvnHeaderRecovery.recoverAudited` adds post-recovery integrity audit.

IMPORTANT: This patch does NOT implement X16R, X16RV2, KawPoW or RVN difficulty
retarget. `ExpectedBits` and PoW/ID checkers MUST be backed by real network
consensus logic. All positive validators in test sources are TEST ONLY.
Anchor ID, anchor height, anchor timestamp and anchor chainwork must be
trusted network-specific values. Do not wire an always-true verifier into production.
Audit checks internal consistency, NOT actual PoW or authenticity.

No Bitcoin module, root pom, app config, P2P or mining changes.
Run: mvn -pl rvn-app -am test
