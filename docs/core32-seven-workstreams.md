# Bitcoin Core 32.1: seven requested workstreams

User request: resolve all seven previously listed compatibility workstreams.
Latest explicit user checklist: [current status of all seven areas](core32-user-checklist.md).
Current expanded eight-direction execution/acceptance:
[core32-eight-acceptance.md](core32-eight-acceptance.md).
These are project-sized workstreams, not seven individual defects. A workstream
is closed only after its concrete behavior and independent verification match
the stated scope; successful packaging alone does not close it.

| # | Workstream | Current acceptance work |
| --- | --- | --- |
| 1 | Low-work header protection | Production PRESYNC/REDOWNLOAD is connected to initial/live/async reception after explicit user approval. Dynamic threshold, salted commitments, bounded lookahead, reset and separate peer cursors are verified by state tests, real P2P tests and the native Core 32.1 wire comparison. Explicit NoBan exemption implemented under 4; RPC statistics remain under 6. See [behavior/evidence and remaining limits](headers-presync-core32.md). |
| 2 | GETHEADERS response ownership and failover | Shared preflight release, missing-ancestry ownership, NoBan and future/cached-invalid policies are implemented. Initial validation/malformed failures select another source; internal state errors remain fatal. Default initial/live timing now uses Core's formula and preferred-peer accounting from VERSION with independent peer workers. See [timing evidence and remaining scheduler limits](header-sync-timing-core32.md). Remaining block/transaction failover still needs comparison. |
| 3 | Compact blocks | Negotiated-version guard before decoding and a one-block GETBLOCKTXN cache exemption implemented and tested. Cached requests still validate indexes; the next connected block replaces the cache. Full optimistic side-block cache/announcement and request-authorization behavior remain to compare. |
| 4 | Peer permissions/manual connections | Immutable local CIDR permissions, NoBan inheritance, misbehavior/local-address rules, manual reconnect and RPC metadata implemented. NoBan PRESYNC/history and Download GETHEADERS exceptions implemented. Manual-only bootstrap now passes real P2P block acceptance and cancellation checks. Broader failover and additional permission flags remain open. See [peer access](peer-access-core32.md) and [manual bootstrap](manual-bootstrap-presync-rpc-core32.md). |
| 5 | Mempool optimizer | Budgeted SFL/CostModel/PostLinearize, retained main/staging graph and orders, fee changes, equal-rate keys, RBF diagrams, memory pressure, oversized reorg Trim and expiry implemented. Independent Core references and native RPC matrices are recorded in [the acceptance report](mempool-core32-completion.md). Historical cache-only notes below are superseded by that report. |
| 6 | Node RPC | AssumeUTXO reporting, snapshot base hash and real PRESYNC height implemented and tested. Native Core method-name inventory is generated; remaining schemas/semantics and missing methods are open. The inventory separates categories, including wallet. See [execution/acceptance](core32-eight-acceptance.md). |
| 7 | Additional networks | Configurable Tor/I2P transports, private broadcast and external ASMap with integration/protocol fixtures. Enabling external services is awaiting user preference and endpoint configuration. |

Do not declare these all resolved by implementing a smaller unrelated helper or
by changing the compatibility report. Preserve the original single-node IDEA
configuration. Stop the production Bitcoin process before Maven compilation and
verify a single healthy restarted process after a completed implementation batch.

## Оставшиеся отличия и непроверенное поведение

Текущий статус подтверждённых направлений:

1. Синхронизация: в стандартном режиме реализованы формула начального срока,
   условия тайм-аутов, NoBan reset и учёт предпочтительных пиров после VERSION.
   Начальный и live пути обслуживают пиры независимо. Нативный Core wire-тест
   проверяет срок и альтернативу для обоих вариантов NoBan.
   Окно VERSION/VERACK исправлено отдельным учётом handshake;
   [реализация и нативные сценарии](preferred-peer-handshake-gap-core32.md).
   Открыты полный scheduler и прочие ошибки источников;
   [реализация, проверки и границы](header-sync-timing-core32.md).
   Предпочтение источников заданий блоков при IBD реализовано. Availability
   GETHEADERS в refresh block policy используют общее состояние координатора;
   [допуск, таймеры и проверки](availability-headers-owner-core32.md).
2. Ручная начальная синхронизация реализована для нулевого автоматического бюджета
   и явных manual-peers. Настоящий P2P regtest-блок принят; готовность майнинга
   и отмена ожидания проверены. [Реализация и проверки](manual-bootstrap-presync-rpc-core32.md).
3. Разрешения пиров: реализованы Download, NoBan и Addr. Для Addr добавлены
   обход адресного token bucket и GETADDR privacy cache 21–27 часов для обычных
   входящих пиров. [Реализация и проверки](addr-permission-core32.md).
   Добавлены Relay/ForceRelay, blocksonly, исключение из лимитов объявлений и
   повторная рассылка принятых транзакций; [проверки и пределы](relay-permissions-core32.md).
   Добавлен [Mempool/BIP35](mempool-permission-core32.md): права доступа и
   фильтрованный по комиссии ответ INV. Bloom, upload-target/GETDATA sequence
   и полная нативная wire-матрица остаются открытыми.
4. Mempool: локальный exact maximum-closure solver отличается от ограниченного
   бюджетом spanning-forest optimizer Core; различается fallback равных ставок,
   а результаты при исчерпании бюджета и инкрементальных изменениях ещё требуют
   независимого сопоставления. Исправлено избыточное построение графа и копирование
   пула при бесконфликтном приёме/восстановлении; [проверки](mempool-admission-local-work-core32.md).
   Также исправлены RBF-проверка лимита до оптимизации и расчёт размера кластера
   по суммарному adjusted weight; [проверки](rbf-cluster-preflight-core32.md).
   Устранена повторная TRUC-проверка исторических предков после reorg;
   [сценарий и пределы исправления](truc-reorg-inheritance-core32.md).
   Реализовано TRUC-исключение для прямой замены потомка в семье после reorg;
   косвенное удаление через другую ветку исключения не даёт.
   [Проверки и оставшийся объём](truc-reorg-replacement-core32.md).
   По live-стеку устранён квадратичный обход при RBF-сравнении диаграмм;
   [доказательства и проверки](rbf-diagram-traversal-core32.md). Это не заменяет
   бюджетный оптимизатор Core.
   Исправлены округление adjusted weight вытесненного пакета и обновление
   rolling floor при нулевой/отрицательной ставке; [проверки и пределы](eviction-chunk-floor-core32.md).
   Package-RBF теперь считает конфликтующие кластеры, а не сумму потомков;
   [проверки 120 транзакций и границы 100/101](package-rbf-cluster-count-core32.md).
   Добавлены ранний отказ сверх лимита и однократное построение связей на
   проверку; [535 mempool-проверок и оставшийся объём](rbf-preflight-work-core32.md).
   Ephemeral dust теперь требует нулевых base fee и modified fee одновременно;
   [проверки и нативное сравнение](ephemeral-dust-base-fee-core32.md).
   Обход потомков больше не пересканирует весь пул на каждом уровне графа;
   [300 DAG, цепочка 10 000 записей и пределы](descendant-traversal-core32.md).
   Одиночный RBF больше не оптимизирует несвязанные кластеры;
   [сравнение 300 DAG и пределы](rbf-affected-diagrams-core32.md).
   Выбор chunk для вытеснения не пересканирует потомков всего пула для
   каждого кластера; [проверки и пределы](eviction-tail-work-core32.md).
   TRUC sibling-кандидат ищется через spent index без сканирования пула;
   [500 DAG и пределы](sibling-conflict-index-core32.md).
   Комиссии, RBF и перспективные ограничения проверяются до исполнения скриптов;
   [проверки приоритета и пределы](admission-script-order-core32.md).
   Отрицательная modified fee после prioritisation даёт отказ политики,
   а не ошибку аргумента; [проверки и пределы](negative-modified-fee-core32.md).
   Положительный rolling minimum проверяется до minrelay;
   [проверки приоритета и пределы](fee-floor-priority-core32.md).
   Имена одиночных RPC-отказов minrelay/rolling minimum приведены к Core;
   [проверки и пределы](fee-rpc-reasons-core32.md).
   Числовые reject-details одиночных комиссионных отказов передаются
   структурированно; [сравнение и пределы](fee-rpc-details-core32.md).
   Пакетные комиссионные отказы сохраняют индекс и детали вместо общей
   package-error; [сравнение и пределы](package-fee-index-core32.md).
   Скрипты пакетной пробы исполняются после предварительных проверок всех
   транзакций; [нативное сравнение и пределы](package-precheck-phase-core32.md).
   TRUC/cluster/ephemeral проверки пробного пакета выделены в общую фазу;
   [проверки и пределы](package-shared-policy-phase-core32.md).
Скриптовый отказ пробы сохраняет успешный префикс и подробности входа;
[реализация и оставшаяся диагностика](package-script-results-core32.md).
RPC submitpackage поддерживает частичный приём родителей, результаты каждой
транзакции и новые effective-fee metadata;
[границы реализации и проверки](submitpackage-partial-core32.md).
Повторная CPFP-оценка выполняет общие проверки до скриптов;
[порядок, независимые сценарии и ограничения](shared-submission-phase-core32.md).
Добавлены waitfornewblock/waitforblock/waitforblockheight с эталонными схемами,
help и сравнениями ошибок; [RPC-объём](core32-rpc-execution.md).
5. RPC: набор методов и вложенных схем остаётся подмножеством Core. Поле
   presynced_headers реализовано и проверено. Нативное сравнение теперь выгружает
   инвентарь методов; полная семантика остальных методов ещё не реализована.
   Для одиночного testmempoolaccept обе причины превышения лимита кластера
   приведены к Core-имени too-large-cluster; проверен нативный RBF-сценарий.
   Multi-transaction testmempoolaccept переведён в режим индивидуальных комиссий
   без CPFP-агрегации; исправлены effective-feerate/effective-includes.
   [Проверки и оставшийся объём RPC](testmempoolaccept-fee-mode-core32.md).
   Пробы допускают общую топологическую форму пакета; предварительная проверка
   submitpackage использует собственные CPFP/RBF-правила.
   [Сценарии и пределы](package-probe-topology-core32.md).
   Имена, приоритет и RPC-форма структурных отказов пакета приведены к Core;
   [проверки и открытые частичные результаты](package-structural-errors-core32.md).
   maxfeerate в testmempoolaccept использует base fee, округление Core и
   последовательную выдачу результатов; [проверки и пределы](testaccept-maxfee-core32.md).
   Одиночная проба различает точный повтор и тот же txid с другим witness;
   [нативное сравнение и пределы](known-single-probe-core32.md).
   Пакетная проба больше не дедуплицирует известные транзакции и сообщает
   индекс индивидуального отказа; [проверки и пределы](known-package-probe-core32.md).
   submitpackage сохраняет известный witness и сообщает other-wtxid;
   [дедупликация и оставшийся объём](submission-witness-dedup-core32.md).
   maxfeerate не применяется повторно к известным записям submitpackage;
   [проверки и пределы](known-submission-maxfee-core32.md).
   missing-inputs в одиночной и пакетной пробе имеет Core-форму с индексом
   отказа и незавершёнными остальными записями;
   [проверки и пределы](missing-input-probe-core32.md).
6. Сеть: отсутствуют полноценные Tor/I2P-транспорты, private broadcast и загрузка
   внешнего ASMap. Разбор адресов BIP155 сам по себе эти функции не реализует.

Области, где полное совпадение пока не подтверждено проверками:

7. Compact blocks: оптимистичный кеш и объявления боковых блоков, полный набор
   условий авторизации запросов. Наличие отличия в каждом конкретном сценарии
   ещё нужно установить, а не считать весь раздел одной найденной ошибкой.
8. Переключение источников при остальных ошибках блоков/транзакций: полный набор
   сценариев и классификация последствий для пира ещё не сопоставлены с Core.

Это исходные восемь направлений проверки/доработки с текущими статусами, а не точное количество
отдельных ошибок. Исходные семь workstreams в таблице выше — более крупные группы.
