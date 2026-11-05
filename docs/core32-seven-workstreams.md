# Bitcoin Core 32.1: seven requested workstreams

User request: resolve all seven previously listed compatibility workstreams.
Current expanded eight-direction execution/acceptance:
[core32-eight-acceptance.md](core32-eight-acceptance.md).
These are project-sized workstreams, not seven individual defects. A workstream
is closed only after its concrete behavior and independent verification match
the stated scope; successful packaging alone does not close it.

| # | Workstream | Current acceptance work |
| --- | --- | --- |
| 1 | Low-work header protection | Production PRESYNC/REDOWNLOAD is connected to initial/live/async reception after explicit user approval. Dynamic threshold, salted commitments, bounded lookahead, reset and separate peer cursors are verified by state tests, real P2P tests and the native Core 32.1 wire comparison. Explicit NoBan exemption implemented under 4; RPC statistics remain under 6. See [behavior/evidence and remaining limits](headers-presync-core32.md). |
| 2 | GETHEADERS response ownership and failover | Shared preflight release implemented for pending and asynchronous replies; missing ancestry retains ownership. Live NoBan timeout and future/cached-invalid policies implemented. Initial header-validation/malformed-response failures now try another source instead of failing startup; internal state errors remain fatal. See [initial regression/evidence](initial-header-rejection-core32.md), [live timeout](live-header-timeout-core32.md) and [live rejection](live-header-rejection-core32.md). Complete timing/preferred-peer policy and remaining block/transaction failover still need comparison. |
| 3 | Compact blocks | Negotiated-version guard before decoding and a one-block GETBLOCKTXN cache exemption implemented and tested. Cached requests still validate indexes; the next connected block replaces the cache. Full optimistic side-block cache/announcement and request-authorization behavior remain to compare. |
| 4 | Peer permissions/manual connections | Immutable local CIDR permissions, NoBan inheritance, misbehavior/local-address rules, manual reconnect and RPC metadata implemented. NoBan PRESYNC/history and Download GETHEADERS exceptions implemented. Manual-only bootstrap now passes real P2P block acceptance and cancellation checks. Broader failover and additional permission flags remain open. See [peer access](peer-access-core32.md) and [manual bootstrap](manual-bootstrap-presync-rpc-core32.md). |
| 5 | Mempool optimizer | Differential checks for bounded optimizer work and fallback ordering, including incremental mutations; optimal unlimited reference cases alone do not close this. |
| 6 | Node RPC | AssumeUTXO reporting, snapshot base hash and real PRESYNC height implemented and tested. Native Core method-name inventory is generated; remaining schemas/semantics and missing methods are open. The inventory separates categories, including wallet. See [execution/acceptance](core32-eight-acceptance.md). |
| 7 | Additional networks | Configurable Tor/I2P transports, private broadcast and external ASMap with integration/protocol fixtures. Enabling external services is awaiting user preference and endpoint configuration. |

Do not declare these all resolved by implementing a smaller unrelated helper or
by changing the compatibility report. Preserve the original single-node IDEA
configuration. Stop the production Bitcoin process before Maven compilation and
verify a single healthy restarted process after a completed implementation batch.

## Оставшиеся отличия и непроверенное поведение

Текущий статус подтверждённых направлений:

1. Синхронизация: полная формула начального срока загрузки заголовков, условия
   тайм-аутов и учёт предпочтительных пиров Core ещё не воспроизведены. Live-путь
   использует собственный пятисекундный предел ожидания. Исключения NoBan для
   тайм-аута уже реализованы в live и начальном пути;
   [проверка начального пути](initial-noban-timeout-core32.md).
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
5. RPC: набор методов и вложенных схем остаётся подмножеством Core. Поле
   presynced_headers реализовано и проверено. Нативное сравнение теперь выгружает
   инвентарь методов; полная семантика остальных методов ещё не реализована.
   Для одиночного testmempoolaccept обе причины превышения лимита кластера
   приведены к Core-имени too-large-cluster; проверен нативный RBF-сценарий.
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
