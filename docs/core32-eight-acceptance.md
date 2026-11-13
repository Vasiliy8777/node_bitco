# Выполнение всех восьми направлений

Объём: все восемь направлений из текущего аудита Bitcoin Core 32.1. Успешная
сборка не означает, что незавершённые функции уже реализованы. Рабочая
конфигурация ноды не получает автоматически новых доверенных пиров или внешних
сетевых сервисов. Промежуточные изменения проверяются перед перезапуском одной
исходной IDEA-ноды.

| № | Направление | Состояние и критерий завершения |
| --- | --- | --- |
| 1 | Сроки синхронизации / предпочтительные пиры | Формула, строгие условия срока, NoBan reset и учёт предпочтительных пиров после VERSION реализованы в стандартном режиме. Независимые задачи пиров заменяют последовательное ожидание. Управляемые часы и нативный двухпировый Core wire-тест подтверждают границы. Учёт VERSION/VERACK исправлен отдельным набором handshake-пиров. Полный scheduler и import/reindex остаются открытыми. [Проверки и границы](header-sync-timing-core32.md). |
| 2 | Начальная синхронизация только через ручные подключения | Реализовано для `maxconnections=0` с явным `manual-peers`: ожидание соединения, загрузка заголовков/блока, минимальная работа, live-sync и готовность майнинга без автоматических слотов. Настоящий P2P regtest-блок принят; отмена ожидания проверена. Полная сборка и рабочий перезапуск фиксируются отдельно. |
| 3 | Остальные разрешения пиров | Частично. [Addr](addr-permission-core32.md), [Relay/ForceRelay](relay-permissions-core32.md) и [Mempool/BIP35](mempool-permission-core32.md) имеют реальные обработчики и проверки. Для Mempool реализованы доступ, trickle, feefilter по базовой комиссии, MSG_TX/MSG_WTX и INV ≤50 000. Bloom, upload-target/GETDATA sequence и полная нативная wire-матрица остаются открытыми. |
| 4 | Оптимизатор mempool | Реализован бюджетный SFL с CostModel, PostLinearize, continuation и сохранёнными main/staging состояниями. 9600 точных бюджетных векторов, 3600 снимков графа, 383 пары диаграмм и 512 Trim-сценариев; дополнительно нативные pressure/reorg/expiry. [Реализация и приёмка всего пункта mempool](mempool-core32-completion.md). |
| 5 | RPC | Частично. Добавлен `getpeerinfo.presynced_headers` с реальными PRESYNC/REDOWNLOAD/завершением. Нативная проверка выгружает точный список методов Core и ноды; схемы и семантика каждого отсутствующего node-метода остаются отдельной работой. Инвентарь включает wallet-методы и сам по себе не задаёт объём кошелька. |
| 6 | Дополнительные сети | Открыто. Нужны SOCKS/Tor, I2P SAM, private broadcast и внешний ASMap с локальными протокольными/интеграционными фикстурами. Подключение реальных внешних сервисов не требуется для завершения реализации и не включается автоматически. |
| 7 | Compact blocks | Открыто. Сначала нужен точный разбор условий оптимистичного кеша/объявлений и авторизации запросов относительно Core, затем независимые wire-сценарии для найденных отличий. Уже работающий кеш последнего принятого блока не подменяет этот объём. |
| 8 | Ошибки блоков/транзакций и смена источника | Открыто. Существующие coordinator/scheduler/reconnect/compact/relay тесты являются базой. Требуется таблица по классам ошибок и независимое сравнение: наказание/сохранение пира, повтор запроса, смена источника, состояние failed-ветки и отсутствие активации невалидных данных. |

Отчёт по текущей реализации ручной синхронизации и PRESYNC RPC:
[manual-bootstrap-presync-rpc-core32.md](manual-bootstrap-presync-rpc-core32.md).

Оставшиеся отличия: [core32-seven-workstreams.md](core32-seven-workstreams.md).

RPC inventory including hidden Core commands: 176 reference names, 55 local
names, 121 missing names and zero additional names. Categories and complete
lists: [core32-rpc-method-inventory.md](core32-rpc-method-inventory.md).
This is a name inventory, not schema/behavior verification.

TRUC/reorg follow-up: ordinary admission no longer revalidates inheritance on
grandfathered ancestor edges. New direct-edge inheritance and TRUC topology
limits remain enforced. [Implementation, regression and remaining scope](truc-reorg-inheritance-core32.md).
This is a concrete admission fix, not completion of the budgeted optimizer.
The subsequent [TRUC direct-replacement follow-up](truc-reorg-replacement-core32.md)
preserves the original descendant/conflict context for grandfathered families.
Live verification additionally exposed and repaired a quadratic RBF diagram
comparison under the chainstate lock. [Runtime evidence and bounded traversal
checks](rbf-diagram-traversal-core32.md). The full optimizer remains open.
The [eviction/floor follow-up](eviction-chunk-floor-core32.md) fixes aggregate
chunk vsize rounding, zero-rate floor bumps and signed/wide removed-rate arithmetic.
The [package-RBF cluster-count follow-up](package-rbf-cluster-count-core32.md)
shares the single/package bound and removes descendant-count false rejections.
The [early RBF preflight follow-up](rbf-preflight-work-core32.md) checks the
bound before descendant collection/staging and builds connectivity once per check.
The [descendant traversal follow-up](descendant-traversal-core32.md) removes
repeated full-pool scans on reverse-ordered dependency chains. Incremental
TxGraph and the budgeted optimizer remain open under direction 4.
The [ephemeral-dust fee follow-up](ephemeral-dust-base-fee-core32.md) checks both
base and modified fee, preventing cancellation by a pre-registered fee delta.
Its native fixture uses submitpackage. The subsequent
[testmempoolaccept fee-mode correction](testmempoolaccept-fee-mode-core32.md)
removes CPFP aggregation from probes and reports individual effective fees.
Full RPC reason/partial-result shape parity remains open under direction 5.

Latest full indexed-graph-queries batch: 5,430 complete-reactor cases, 5,418 passed,
12 skipped, zero failures/errors; both applications packaged November 12,
22:19:51 Bangkok (`core32-indexed-graph-queries-full-build.log`). Queries and
admission conflict closures use persistent adjacency; batch graph views solve
each requested component once. Both native Core scenarios passed (129.4 seconds).
Full shared staging, incremental linearization and budgeted optimization remain
open. [Evidence and limits](indexed-graph-queries-core32.md).
Original IDEA GPU PID 20332: one process, RUNNING, blocks=headers=975567,
IBD=false, six peers (two block relay), 35,727 mempool entries, zero privileged
peers and accepted CUDA shares (`core32-indexed-graph-queries-live.json`).

Previous full spent-commit-delta batch: 5,422 complete-reactor cases, 5,410 passed,
12 skipped, zero failures/errors; both applications packaged November 12,
21:25:37 Bangkok (`core32-spent-commit-delta-full-build.log`). Spent publication
now reads inputs only for changed txids; metadata/overflow/replacement and 160
random commit cases pass. Both native Core scenarios passed (112.8 seconds).
Full shared staging and budgeted optimization remain open.
[Evidence and limits](spent-commit-delta-core32.md).
Original IDEA GPU PID 36316: one process, RUNNING, blocks=headers=975562,
IBD=false, eight peers (two block relay), 35,345 mempool entries, zero privileged
peers and accepted CUDA shares (`core32-spent-commit-delta-live.json`).

Previous full stage-graph-copy batch: 5,417 complete-reactor cases, 5,405 passed,
12 skipped, zero failures/errors; both applications packaged November 12,
19:33:48 Bangkok (`core32-stage-graph-copy-full-build.log`). Initial staging
copies ready graph/spent indexes without repeated input scans. Isolation and
known-transaction probe regressions passed; both native Core scenarios passed
(97.39 seconds). Full shared staging, incremental linearization and budgeted
optimization remain open. [Evidence and limits](stage-graph-copy-core32.md).
Original IDEA GPU PID 72420: one process, RUNNING, blocks=headers=975552,
IBD=false, nine peers (two block relay), 33,837 mempool entries, zero privileged
peers and accepted CUDA shares (`core32-stage-graph-copy-live.json`).

Previous full graph-commit-delta batch: 5,413 complete-reactor cases, 5,401 passed,
12 skipped, zero failures/errors; both applications packaged November 12,
18:48:22 Bangkok (`core32-graph-commit-delta-full-build.log`). Bulk commits
preserve unchanged main-pool adjacency and components. Both native Core
scenarios passed (118.7 seconds). Full staging and budgeted optimization remain
open. [Implementation and checks](graph-commit-delta-core32.md).
Original IDEA GPU PID 141460: one process, RUNNING, blocks=headers=975545,
IBD=false, six peers (two block relay), 37,797 mempool entries, zero privileged
peers and accepted CUDA shares (`core32-graph-commit-delta-live.json`).

Previous full persistent-main-graph batch: 5,410 complete-reactor cases, 5,398 passed,
12 skipped, zero failures/errors; both applications packaged November 12,
17:22:17 Bangkok (`core32-persistent-graph-full-build.log`). Main-pool adjacency
and components persist between ordinary mutations; unchanged reads use a bounded
revision cache. Bulk staging/reorg and the budgeted optimizer remain open.
[Implementation and evidence](persistent-main-graph-core32.md).
Original IDEA GPU PID 67116: one process, RUNNING, blocks=headers=975536,
IBD=false, eight peers (two block relay), 43,615 mempool entries, zero privileged
peers and accepted CUDA shares (`core32-persistent-graph-live.json`).

Previous full equal-eviction batch: 5,406 complete-reactor cases, 5,394 passed,
12 skipped, zero failures/errors; both applications packaged November 12,
16:12:06 Bangkok. Reverse Core chunk keys replace insertion-order eviction.
Seven regressions cover prefix/max-txid ties and rounded removed rates. Native
memory-pressure/staging and budgeted optimizer comparisons remain open.
Original IDEA GPU PID 47076: one process, RUNNING, blocks=headers=975534,
IBD=false, ten peers (two block relay), 35,438 mempool entries, zero privileged
peers, accepted CUDA shares. [Evidence and limits](equal-rate-eviction-core32.md).

Previous full chunk-fallback batch: 5,399 complete-reactor cases, 5,387 passed,
12 skipped, zero failures/errors; both applications packaged November 12,
15:48:59 Bangkok. Core GBT, Java graph and Java GBT agree on signed equal-fee/
weight transactions. Shared keys cover fee rate, equal-rate prefix weight and
maximum serialized txid, including tight-budget selection. Optimizer budget,
persistent TxGraph and equal-rate eviction remain open.
Original IDEA GPU PID 117012: one process, RUNNING, blocks=headers=975533,
IBD=false, eight peers (two block relay), 34,357 mempool entries, zero privileged
peers and accepted CUDA shares. [Evidence and limits](equal-rate-fallback-core32.md).

Previous accepted-dust priority follow-up: 67 selected cases passed without skips
or failures, including three red/green unit regressions and a new native strict
policy fixture. Both applications packaged with tests skipped afterward.
Original IDEA GPU PID 37916: one process, RUNNING, blocks=headers=975530,
IBD=false, seven peers (two block relay), 36,511 mempool entries, zero privileged
peers, accepted CUDA shares. Targeted validation, not a new full-reactor run.
[Evidence and remaining scope](dust-priority-core32.md).

Previous priority-argument follow-up: 59 selected cases passed without skips or
failures, including 35 new native argument/error comparisons. Both applications
packaged with tests skipped afterward. Original IDEA GPU PID 33472: one process,
RUNNING, blocks=headers=975530, IBD=false, six peers (two block relay), 36,004
mempool entries, zero privileged peers, accepted CUDA shares. Targeted validation,
not a new full-reactor run. [Evidence and scope](rpc-priority-arguments-core32.md).

Previous broadcast-expiry follow-up: 59 selected cases passed without skips or
failures, including four controlled-clock regressions reproduced before the
fix and the existing native RPC/package matrix. Both applications packaged
with tests skipped afterward. Original IDEA GPU PID 82088: one process, RUNNING,
blocks=headers=975528, IBD=false, seven peers (two block relay), 36,850 mempool
entries, zero privileged peers, accepted CUDA shares. Targeted validation;
no new full-reactor claim. [Evidence and scope](broadcast-expiry-core32.md).

Previous broadcast-preflight follow-up: 55 selected cases passed without skips or
failures, including typed failure/no-admission checks, isolated successful probe,
monitor contention and the native RPC/package matrix. Both applications packaged
with tests skipped afterward. One original IDEA GPU PID 94528: RUNNING,
blocks=headers=975489, IBD=false, seven peers (two block relay), 32,457 mempool
entries, zero privileged peers, accepted CUDA shares. This is targeted validation,
not a new full-reactor run. [Evidence and limits](broadcast-preflight-core32.md).

Previous atomic-broadcast follow-up: 52 selected cases passed without skips or
failures, including a deterministic monitor-contention regression and the native
RPC/package matrix. Both applications packaged with tests skipped afterward.
One original IDEA GPU PID 77932: RUNNING, blocks=headers=975472, IBD=false,
three peers (two block relay), 42,889 mempool entries, zero privileged peers,
accepted CUDA shares. Targeted validation, not a new full-reactor run.
[Evidence and remaining limits](atomic-broadcast-core32.md).

Previous broadcast-classification follow-up: 38 selected cases passed without
skips or failures, including nine new native missing-input/confirmed error
comparisons. Both applications packaged with tests skipped afterward; this is
targeted validation, not a new full-reactor run. Original IDEA GPU PID 8660:
one process, RUNNING, blocks=headers=975466, IBD=false, seven peers (two block
relay), 41,518 mempool entries, zero privileged peers and accepted CUDA shares.
[Evidence and remaining scope](rpc-broadcast-classification-core32.md).

Previous sendraw fee-cap/rebroadcast follow-up: 39 selected cases passed without
skips or failures; native comparisons cover base fee vs deltas, ceil boundaries
and known txids with alternate witness. Both applications packaged with tests
skipped afterward. Original IDEA GPU PID 84280: one process, RUNNING,
blocks=headers=975464, IBD=false, nine peers (two block relay), 41,280 mempool
entries, zero privileged peers and accepted CUDA shares. This is targeted
validation, not a new full-reactor run. [Evidence](rpc-sendraw-fee-cap-core32.md).

Previous money-argument/early-send follow-up: 17 selected cases passed without
skips or failures, including 159 additional native comparisons. Both applications
packaged with tests skipped afterward; no new full-reactor claim. Original IDEA
GPU PID 114048: one process, RUNNING, blocks=headers=975461, IBD=false, five peers
(two block relay), 39,937 mempool entries, zero privileged peers, accepted CUDA
shares. [Evidence and remaining limits](rpc-amount-limits-core32.md).

Previous package-element type follow-up: 17 selected cases passed without skips
or failures; 50 additional native comparisons cover non-string array elements
and count/decode precedence. Both applications packaged afterward with tests
skipped. This is targeted validation, not another complete-reactor run.
Original IDEA GPU PID 139888: one process, RUNNING, blocks=headers=975453,
IBD=false, four peers (two block relay), 42,379 mempool entries, zero privileged
peers, accepted CUDA shares. [Evidence and limits](package-rpc-decoding-core32.md).

Previous package-RPC decoding batch: 5,378 full-reactor cases (5,366 passed,
12 skipped), zero failures/errors; both applications packaged November 11,
22:31:37 Bangkok. Native comparison adds 26 malformed/count/error-order cases.
TRUC fixture uses a wallet-notification barrier and a live unspent-coin check.
One original IDEA GPU PID 90688: RUNNING, blocks=headers=975450, IBD=false,
eight peers (two block relay), 36,577 mempool entries, zero privileged peers,
accepted CUDA shares. [Evidence and remaining scope](package-rpc-decoding-core32.md).

Previous handshake-preference batch: 5,378 full-reactor cases (5,366 passed,
12 skipped), zero failures/errors; both applications packaged November 11,
22:09:58 Bangkok. VERSION-only alternatives count before READY; native Core
wire cases verify the delayed-VERACK timeout behavior for ordinary/NoBan sources.
One original IDEA GPU process PID 78900: RUNNING, blocks=headers=975447,
IBD=false, five peers (two block relay), 39,667 mempool entries, zero privileged
peers, accepted CUDA shares. [Evidence](preferred-peer-handshake-gap-core32.md).
[All requested areas and remaining work](core32-user-checklist.md).

Previous availability failure-boundary batch: 5,373 full-reactor cases (5,361 passed,
12 skipped), zero failures/errors; both applications packaged on November 8,
21:13:45 Bangkok. Local locator/state failures propagate; transport-close races
remain retryable and release GETHEADERS admission. Live recheck on November 11,
21:46 Bangkok: one Bitcoin process, PID 51816, blocks=headers=975447, IBD=false,
eleven peers (three block relay), 37,799 mempool entries, zero privileged peers
and accepted CUDA shares. [Evidence and limits](availability-failure-boundary-core32.md).

Previous availability ownership batch: 5,364 full-reactor cases (5,352 passed,
12 skipped), zero failures/errors; both applications packaged. Availability
GETHEADERS shares initial/live admission, locator and timeout state. Deferred
attempts do not add cooldown, and nonblocking send ownership prevents rollback
races. Original IDEA GPU PID 29204 verified RUNNING, height=headers=975064,
IBD=false, five peers (two block relay), 30,877 mempool entries, zero privileged
peers, accepted CUDA shares and one Bitcoin application process.
[Implementation and remaining limits](availability-headers-owner-core32.md).

Previous IBD source-preference batch: 5,360 full-reactor cases (5,348 passed,
12 skipped), zero failures/errors; both applications packaged. Block assignment
uses preferred-source/fallback rules and a stable eligibility snapshot per pass.
Availability-header requests were subsequently integrated in the follow-up above.
Original IDEA GPU PID 29024 verified RUNNING, height=headers=975049,
IBD=false, eight peers (two block relay), 31,818 mempool entries, zero privileged
peers, accepted CUDA shares and one Bitcoin application process.
[Implementation and remaining path](ibd-source-preference-core32.md).

Previous chainwork-null-locator batch: 5,359 full-reactor cases (5,347 passed,
12 skipped), zero failures/errors; both applications packaged. Final chainwork
probes at genesis use a null locator. Native Core wire checks confirm strict
20/2-minute boundaries and initial/final locator forms; Java checks cover cache
transitions and the frozen old benchmark. Original IDEA GPU PID 83732 verified
RUNNING, height=headers=975045, IBD=false, seven peers (two block relay), 34,027
mempool entries, zero privileged peers, accepted CUDA shares and one Bitcoin
application process. [Implementation and limits](chainwork-null-locator-core32.md).

Previous chainwork-start gate batch: 5,356 full-reactor cases (5,344 passed,
12 skipped), zero failures/errors; both applications packaged. Deferred peers
do not start chainwork eviction until headers sync is actually admitted.
Strict 20/2-minute boundaries and explicit legacy timeouts are preserved.
Original IDEA GPU PID 15448 verified RUNNING, height=headers=975044,
IBD=false, eight peers (two block relay), 35,075 mempool entries, zero privileged
peers, accepted CUDA shares and one Bitcoin application process.
[Implementation and limits](chainwork-start-gate-core32.md).

Previous initial-locator batch: 5,355 full-reactor cases (5,343 passed,
12 skipped), zero failures/errors; both applications packaged. The first
GETHEADERS starts from the best header's parent (genesis remains genesis),
using one tip snapshot for deadline and locator. Gate retry and continuation
semantics are covered by two explicit vectors. Original IDEA GPU PID 127368
verified RUNNING, height=headers=975042, IBD=false, seven peers (two block relay),
35,204 mempool entries, zero privileged peers, accepted CUDA shares and one
Bitcoin application process. [Implementation and limits](initial-locator-core32.md).

Previous initial-recovery ownership batch: 5,353 full-reactor cases (5,341 passed,
12 skipped), zero failures/errors; both applications packaged. Initial requests
for seed and replacement peers delegate to the download coordinator, preserving
preferred-source admission and its timer. Chainwork probes remain separate.
Original IDEA GPU PID 147092 verified RUNNING, height=headers=975042,
IBD=false, seven peers (two block relay), 32,819 mempool entries, zero privileged
peers, accepted CUDA shares and one Bitcoin application process.
[Implementation and evidence](initial-recovery-owner-core32.md).

Previous lifecycle-fixture follow-up: 5,352 full-reactor cases (5,340 passed,
12 skipped), zero failures/errors; both applications packaged. Repeated
locators and maintenance PING no longer falsely close the test source. One
deterministic wire regression and ten repeated failovers passed. Original
IDEA GPU PID 143444 verified RUNNING, height=headers=975036, IBD=false,
eight peers (two block relay), 19,947 mempool entries, no privileged peers,
accepted CUDA shares and one Bitcoin application process.
[Diagnosis, scope and evidence](header-failover-fixture-core32.md).

Previous header-timing/TRUC batch: 5,351 full-reactor cases (5,339 passed,
12 skipped), zero failures/errors; Bitcoin and RVN applications packaged.
Native Core wire cases confirm timeout boundaries and alternatives for ordinary
and NoBan sources; full RPC comparisons confirm TRUC inheritance details and
priority before economic RBF checks. Original IDEA GPU PID 19404 verified
RUNNING, height=headers=975028, IBD=false, ten peers (two block relay),
28,466 mempool entries, zero privileged peers, accepted CUDA shares and exactly
one Bitcoin application process. [Timing scope](header-sync-timing-core32.md),
[remaining mempool work](mempool-core32-execution.md).

Previous RPC chain-wait batch: 5,338 full-reactor cases (5,326 passed,
12 skipped), zero failures/errors; both applications packaged. Three new
methods have full native OpenRPC/help, 12 successful/25 error response
comparisons, and asynchronous same-block waiting. Five local tests include
reorg, spurious wakeups and shutdown registration races. Original IDEA GPU
PID 145416 verified RUNNING, height=headers=974991, IBD=false, nine peers
(two block relay), 30,939 mempool entries, accepted CUDA shares and one
Bitcoin process. All three methods exercised on the live node; catalog has
55 implemented names and 121 missing names.
[RPC scope and remaining work](core32-rpc-execution.md).

Previous shared-submission-phase batch: 5,333 full-reactor cases (5,321 passed,
12 skipped), zero failures/errors; both applications packaged. Native Core
32.1 full RPC comparisons confirm later missing-input/aggregate-fee/ephemeral
failures precede invalid parent scripts, and script failures do not commit
a partial retry. Original IDEA GPU PID 75688 verified RUNNING,
height=headers=974984, IBD=false, seven peers (two block relay),
24,825 mempool entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and remaining scope](shared-submission-phase-core32.md).

Previous partial-submission batch: 5,327 full-reactor cases (5,315 passed,
12 skipped), zero failures/errors; both applications packaged. Native Core
32.1 full RPC/pool comparisons cover committed parents on child failure,
known transactions, corrected child witness, CPFP metadata, client caps,
topology and per-output burn limits/priority. Original IDEA GPU PID 70084
verified RUNNING, height=headers=974981, IBD=false, seven peers (two block
relay), 26,052 mempool entries, accepted CUDA shares and one Bitcoin process.
[Implementation and remaining scope](submitpackage-partial-core32.md).

Previous script-results batch: 5,322 full-reactor cases (5,310 passed,
12 skipped), zero failures/errors; both applications packaged. Native Core
32.1 full RPC comparison covers script failures at every package position,
successful prefixes, unfinished suffixes, maxfeerate precedence, single
probes and six witness failure variants. Original IDEA GPU PID 77936 verified
RUNNING, height=headers=974978, IBD=false, seven peers (two block relay),
23,868 mempool entries and exactly one Bitcoin process.
[Implementation and remaining diagnostics](package-script-results-core32.md).

Previous shared-policy-phase batch: 5,319 full-reactor cases (5,307 passed,
12 skipped), zero failures/errors; both applications packaged. Native Core
32.1 comparison confirms missing-input precedence over in-package TRUC topology
and invalid signatures. Original IDEA GPU PID 131144 verified RUNNING,
height=headers=974973, IBD=false, six peers (two block relay), 24,426 mempool
entries and exactly one Bitcoin process. [Scope and remaining work](mempool-core32-execution.md).

Previous package-precheck-phase batch: 5,314 full-reactor cases (5,302 passed,
12 skipped), zero failures/errors. Native Core 32.1 comparison confirms a later
missing input precedes an earlier corrupted signature, with full RPC equality.
Both applications packaged. Original IDEA GPU PID 124220 verified RUNNING,
height=headers=974936, IBD=false, seven peers (two block relay), 35,889 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](package-precheck-phase-core32.md).

Earlier package-fee-index batch: 5,311 full-reactor cases (5,299 passed,
12 skipped), zero failures/errors. Native Core 32.1 comparison checks complete
RPC responses for parent/child fee failures, including negative modified fees.
Both applications packaged. Original IDEA GPU PID 45036 verified RUNNING,
height=headers=974935, IBD=false, ten peers (two block relay), 31,287 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](package-fee-index-core32.md).

Earlier fee-RPC-details batch: 5,310 full-reactor cases (5,298 passed,
12 skipped), zero failures/errors. Existing native scenarios now require equal
complete RPC objects for zero/negative modified-fee failures, including numeric details.
Both applications packaged. Original IDEA GPU PID 73584 verified RUNNING,
height=headers=974932, IBD=false, six peers (two block relay), 33,988 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](fee-rpc-details-core32.md).

Earlier fee-RPC-reason batch: 5,310 full-reactor cases (5,298 passed,
12 skipped), zero failures/errors. Native Core 32.1 comparison now requires
equal minrelay reject-reason for zero/negative modified-fee scenarios.
Both applications packaged. Original IDEA GPU PID 50404 verified RUNNING,
height=headers=974930, IBD=false, six peers (two block relay), 29,771 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](fee-rpc-reasons-core32.md).

Earlier fee-floor-priority batch: 5,308 full-reactor cases (5,296 passed,
12 skipped), zero failures/errors. Local eviction scenarios verify positive
rolling-floor priority, zero-floor behavior and CPFP; native Core 32.1 regression passed.
Both applications packaged. Original IDEA GPU PID 64748 verified RUNNING,
height=headers=974926, IBD=false, seven peers (two block relay), 23,688 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](fee-floor-priority-core32.md).

Earlier negative-modified-fee batch: 5,304 full-reactor cases (5,292 passed,
12 skipped), zero failures/errors. Native Core 32.1 scenario confirms a negative
modified fee causes fee-policy rejection rather than an argument error;
local checks preserve CPFP submission. Both applications packaged.
Original IDEA GPU PID 73396 verified RUNNING, height=headers=974925,
IBD=false, nine peers (two block relay), 22,506 mempool entries,
accepted CUDA shares and one Bitcoin process.
[Implementation and limits](negative-modified-fee-core32.md).

Earlier admission-script-order batch: 5,301 full-reactor cases (5,289 passed,
12 skipped), zero failures/errors. Native Core 32.1 scenario confirms fee
rejection precedes execution of a corrupted signature. Text parity remains open.
Both applications packaged. Original IDEA GPU PID 60556 verified RUNNING,
height=headers=974921, IBD=false, ten peers (two block relay), 27,147 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](admission-script-order-core32.md).

Earlier sibling-index batch: 5,298 full-reactor cases (5,286 passed,
12 skipped), zero failures/errors. Includes 500 DAG candidate comparisons,
a no-pool-scan guard with 10,000 unrelated entries and native Core 32.1 scenarios.
Both applications packaged. Original IDEA GPU PID 56836 verified RUNNING,
height=headers=974905, IBD=false, nine peers (two block relay), 37,532 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](sibling-conflict-index-core32.md).

Earlier eviction-tail-work batch: 5,296 full-reactor cases (5,284 passed,
12 skipped), zero failures/errors. Includes 300 DAG closure/fee/weight comparisons,
a one-pass guard with 3,000 clusters and native Core 32.1 scenarios.
Both applications packaged. Original IDEA GPU PID 32684 verified RUNNING,
height=headers=974886, IBD=false, nine peers (two block relay), 25,776 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](eviction-tail-work-core32.md).

Earlier affected-RBF-diagram batch: 5,294 full-reactor cases (5,282 passed,
12 skipped), zero failures/errors. Includes 300 random signed-fee DAG comparisons,
an unrelated-entry guard with 10,000 entries and native Core 32.1 scenarios.
Both applications packaged. Original IDEA GPU PID 29108 verified RUNNING,
height=headers=974866, IBD=false, six peers (two block relay), 35,624 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](rbf-affected-diagrams-core32.md).

Earlier missing-input-probe batch: 5,292 full-reactor cases (5,280 passed,
12 skipped), zero failures/errors. Native Core 32.1 comparison checks complete
RPC responses for missing inputs in single and multi-transaction probes.
Both applications packaged. Original IDEA GPU PID 25008 verified RUNNING,
height=headers=974860, IBD=false, nine peers (two block relay), 27,161 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](missing-input-probe-core32.md).

Earlier known-submission-maxfee batch: 5,289 full-reactor cases (5,277 passed,
12 skipped), zero failures/errors. Native Core 32.1 comparison checks repeated
known entries under a 1 sat/kvB maxfeerate, including alternate witness.
New transactions retain the cap. Both applications packaged. Original IDEA GPU
PID 91048 verified RUNNING, height=headers=974852, IBD=false, seven peers
(two block relay), 28,630 mempool entries, accepted CUDA shares and one Bitcoin process.
[Implementation and remaining scope](known-submission-maxfee-core32.md).

Earlier submission-witness batch: 5,287 full-reactor cases (5,275 passed,
12 skipped), zero failures/errors. Native Core 32.1 comparison checks
submitpackage's package_msg and complete tx-results for exact repeats and
different witnesses of already-known transactions. Both applications packaged.
Original IDEA GPU PID 138876 verified RUNNING, height=headers=974843,
IBD=false, eight peers (one block relay), 29,827 mempool entries,
accepted CUDA shares and one Bitcoin process.
[Implementation and remaining scope](submission-witness-dedup-core32.md).

Earlier known-package-probe batch: 5,284 full-reactor cases (5,272 passed,
12 skipped), zero failures/errors. Native Core 32.1 comparison checks known
transactions with matching/different witness in both package positions and
unfinished-result RPC shape. Both applications packaged. Original IDEA GPU
PID 128816 verified RUNNING, height=headers=974831, IBD=false, eight peers
(two block relay), 33,139 mempool entries, accepted CUDA shares and one Bitcoin process.
[Implementation and remaining scope](known-package-probe-core32.md).

Earlier known-single-probe batch: 5,282 full-reactor cases (5,270 passed,
12 skipped), zero failures/errors. Native Core 32.1 comparison checks complete
RPC responses for exact repeats and same-txid/different-witness transactions.
Both applications packaged. Original IDEA GPU PID 145924 verified RUNNING,
height=headers=974825, IBD=false, eight peers (two block relay), 39,114 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](known-single-probe-core32.md).

Earlier testmempoolaccept-maxfee batch: 5,280 full-reactor cases (5,268 passed,
12 skipped), zero failures/errors. Native Core 32.1 comparison checks base-fee
caps with positive/negative mining deltas, satoshi rounding and ordered early exit.
Both applications packaged. Original IDEA GPU PID 6160 verified RUNNING,
height=headers=974810, IBD=false, six peers (one block relay), 36,005 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](testaccept-maxfee-core32.md).

Earlier structural-package-error batch: 5,273 full-reactor cases (5,261 passed,
12 skipped), zero failures/errors. Native Core 32.1 comparison checks complete
RPC rejection objects, error names and sorting-before-conflict precedence.
Both applications packaged. Original IDEA GPU PID 60456 verified RUNNING,
height=headers=974809, IBD=false, five peers (two block relay), 28,821 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](package-structural-errors-core32.md).

Earlier package-probe-topology batch: 5,267 full-reactor cases (5,255 passed,
12 skipped), zero failures/errors, including native Core 32.1 comparison of
three-generation and disconnected probes. RPC submitpackage uses a separate
CPFP/RBF submission preflight. Both applications packaged. Original IDEA GPU
PID 120708 verified RUNNING, height=headers=974803, IBD=false, nine peers
(two block relay), 27,377 mempool entries, accepted CUDA shares and one Bitcoin process.
[Implementation and remaining scope](package-probe-topology-core32.md).

Earlier descendant-traversal batch: 5,261 full-reactor cases (5,249 passed,
12 skipped), zero failures/errors, including the native Core 32.1 comparison.
Both applications packaged. Original IDEA GPU PID 71756 verified RUNNING,
height=headers=974802, IBD=false, eight peers (two block relay), 27,778 mempool
entries, accepted CUDA shares and exactly one Bitcoin process.
[Implementation and limits](descendant-traversal-core32.md).

Earlier individual-probe-fee batch: 5,257 full-reactor cases (5,245 passed,
12 skipped), zero failures/errors, including native Core 32.1 comparison of
the probe decision, vsize and per-transaction fees, with both pools unchanged.
Both applications packaged. Original IDEA GPU PID 123980 reached RUNNING:
height=headers=974789, IBD=false, seven peers (two block relay), 19,061 mempool
entries, accepted CUDA shares and exactly one Bitcoin process verified.
[Implementation and remaining RPC scope](testmempoolaccept-fee-mode-core32.md).

Earlier ephemeral-dust/base-fee batch: 5,252 full-reactor cases (5,240 passed,
12 skipped), zero failures/errors, both applications packaged. The signed
native Core submitpackage cancellation case rejects dust and leaves both pools
unchanged. Original IDEA GPU node PID 130760 reached RUNNING; height/headers
974766/974766, IBD=false, eight peers, 23,069 mempool entries, accepted GPU
shares and one Bitcoin process were verified. [Implementation and remaining
scope](ephemeral-dust-base-fee-core32.md).

Earlier early-RBF-preflight/work batch: 5,248 full-reactor cases (5,236 passed,
12 skipped), zero failures/errors, both applications packaged. Original IDEA
GPU node PID 143276 reached RUNNING; height/headers 974764/974764, IBD=false,
nine peers, 23,005 mempool entries, accepted GPU shares and one Bitcoin process
were verified. [Implementation and remaining scope](rbf-preflight-work-core32.md).

Earlier package-RBF cluster-count batch: 5,243 full-reactor cases (5,231 passed,
12 skipped), zero failures/errors, both applications packaged. Original IDEA
GPU node PID 108060 reached RUNNING; height/headers 974762/974762, IBD=false,
eight peers, 23,605 mempool entries, accepted GPU shares and exactly one Bitcoin
process were verified. [Implementation and remaining scope](package-rbf-cluster-count-core32.md).

Earlier eviction/floor batch: 5,240 full-reactor cases (5,228 passed, 12 skipped),
zero failures/errors, both applications packaged. Original IDEA GPU node
PID 137688 reached RUNNING; height/headers 974758/974758, IBD=false, seven peers,
23,191 mempool entries, accepted GPU shares and exactly one Bitcoin process
were verified. [Implementation, checks and remaining scope](eviction-chunk-floor-core32.md).

Earlier TRUC direct-conflict/original-count batch: 5,235 full-reactor cases
(5,223 passed, 12 skipped), zero failures/errors, both applications packaged.
Original IDEA GPU node PID 125584 reached RUNNING; height/headers 974754/974754,
IBD=false, eight peers, 26,672 mempool entries, accepted GPU shares and exactly
one Bitcoin process were verified. [Implementation and remaining scope](truc-reorg-replacement-core32.md).

Earlier TRUC/reorg and RBF traversal batch: 5,230 full-reactor cases (5,218
passed, 12 skipped), zero failures/errors, both applications packaged. Original
IDEA GPU node PID 135356 reached RUNNING; height/headers 974692/974692,
IBD=false, seven peers, 32,246 mempool entries, responsive RPC, accepted GPU
shares and exactly one Bitcoin process were verified. [TRUC fix](truc-reorg-inheritance-core32.md),
[runtime stall and traversal fix](rbf-diagram-traversal-core32.md).

Earlier ordinary INV/base-fee batch: 5,225 full-reactor cases (5,213 passed,
12 skipped), zero failures/errors; both applications packaged. Original IDEA
GPU node PID 130828 reached RUNNING; height/headers 974688/974688, IBD=false,
eight connections, 37,866 mempool entries, accepted GPU shares and exactly one
Bitcoin process were verified. [Implementation and scope](ordinary-relay-base-fee-core32.md).
This closes the ordinary INV modified-fee/filter discrepancy, not the broader
budgeted optimizer or the other open directions.

Earlier Mempool/BIP35 batch: 5,224 full-reactor cases (5,212 passed, 12 skipped),
zero failures/errors, both applications packaged. Original IDEA GPU node PID
130884 reached RUNNING; height/headers 974686/974686, IBD=false, five peers,
31,848 mempool transactions, accepted CUDA shares and exactly one Bitcoin
process were verified. [Implementation and remaining scope](mempool-permission-core32.md).

Earlier Relay/ForceRelay batch: 5,215 full-reactor cases (5,203 passed, 12 skipped),
zero failures/errors, both applications packaged. Original IDEA GPU node
PID 131136 reached RUNNING; height/headers 974681/974681, IBD=false, seven peers,
29,899 mempool entries, accepted GPU shares and one Bitcoin process were verified.
See [implementation, checks and remaining scope](relay-permissions-core32.md).

Earlier ADDR batch: 5,207 full-reactor cases (5,195 passed, 12 skipped), zero
failures/errors, both applications packaged. Original IDEA GPU node PID 128836
reached RUNNING; RPC height/headers 974669/974669 and IBD=false were verified.
This completed the ADDR portion only. The later Relay/ForceRelay implementation
is described above; the later Mempool/BIP35 implementation is linked in the
direction 3 row. Bloom and complete native permission comparisons
remain open. See [ADDR implementation and verification](addr-permission-core32.md).

Manual/bootstrap verification baseline: 5,189 full-reactor cases (5,177 passed, 12 skipped), no
failures/errors, both applications packaged. The final hidden-method inventory
also passed the separate native Core test. Original single IDEA node restarted
as PID 126060 and verified RUNNING/IBD=false at height 974660. Thus direction 2's
implementation batch is verified; the other seven directions are not closed.

Direction 4's observed conflict-free restoration graph/copy bottleneck is now
repaired and passes allocation and 400-DAG differential checks; the complete
budgeted Core optimizer remains open. See
[implementation and remaining scope](mempool-admission-local-work-core32.md).

Further concrete differences repaired under directions 4/5: RBF checks prospective
cluster limits before diagram optimization, cluster size uses aggregate adjusted
weight, and the single-transaction RPC reports Core's too-large-cluster reason.
The native 65-transaction replacement scenario and graph boundary cases are
documented in [RBF/weight/RPC verification](rbf-cluster-preflight-core32.md).
The final source passed all 5,199 full-reactor cases (5,187 passed, 12 skipped),
including native Core's signed replacement scenario. Original IDEA GPU process
PID 129184 was verified RUNNING at height 974668 with IBD=false, 23,428 mempool
transactions, eight peers and GPU accepted shares. Direction 4's budgeted
optimizer and the broader open directions are still not closed by these fixes.

The mempool follow-up passed 5,191 full-reactor cases (5,179 passed, 12 skipped),
then all 506 mempool cases again after the final count-cap refinement. Both
applications repackaged successfully. Original IDEA GPU process PID 127816 was
verified RUNNING at height 974664 with IBD=false, 24,180 mempool entries and GPU
accepted shares. Observed initialization was 8.534 seconds versus 324.841 on the
prior run; workload differences preclude treating this as a controlled benchmark.
