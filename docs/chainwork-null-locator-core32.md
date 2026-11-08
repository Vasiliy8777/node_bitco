# Chainwork-проба от genesis: пустой locator

В Core 32.1 ConsiderEviction завершающий GETHEADERS строится через
`GetLocator(work_header->pprev)`. Когда benchmark — genesis, родителя нет
и locator пуст. Начальная синхронизация имеет отдельное правило: её первый
locator в этом случае содержит genesis.

NodeRelayService ошибочно применял начальное правило к завершающей chainwork-
пробе и подставлял genesis. Теперь для genesis используется пустой locator;
для остальных вершин — обычный locator от родителя benchmark. Кеш обновляется
при переходе к новой вершине и возвращении к genesis. Запрос сохраняет нулевой
stop hash, как Core.

Независимый нативный тест `coreChainworkFinalProbeUsesNullLocatorAtGenesis`
подключает настоящий Core 32.1 к молчащему Java P2P-пиру как outbound-full-relay.
Через mocktime проверены:

- начальный locator содержит genesis;
- при ровно 20 минутах завершающего запроса нет;
- после 20 минут приходит GETHEADERS с пустым locator и нулевым stop hash;
- при ровно дополнительных двух минутах пир сохраняется, после них отключается.

PING/PONG поддерживает транспорт активным; таким образом, отключение относится
к chainwork-политике, а не к отсутствию транспортной активности. Core запускается
с отдельным временным datadir и завершается в cleanup.

NodeRelayServiceTest проверяет форму отправленного Java-сообщения, кеш для
genesis/потомка/возврата к genesis. PeerChainSyncPolicyTest дополнительно
подтверждает: рост локальной вершины не подменяет сохранённый locator старого
benchmark, пока пир не достиг его работы. После достижения benchmark новый
срок и locator фиксируются по новой вершине.

Выборочный прогон с настоящим Core прошёл:
`target/core-update-audit/core32-chainwork-null-locator-focused.log`.
Это исправление одного правила запроса; полный scheduler и окно VERSION/VERACK
остаются в [списке ограничений](header-sync-timing-core32.md).

Полный Bitcoin/RVN reactor: 5359 тестов, 5347 успешных, 12 пропущенных,
без ошибок; оба приложения упакованы. Журнал
`target/core-update-audit/core32-chainwork-null-locator-full-build.log`,
8 ноября 2026, 16:43:28 Bangkok. Все три нативных wire-сценария Core прошли.
После перезапуска исходная IDEA GPU-нода PID 83732: RUNNING,
height=headers=975045, IBD=false, семь пиров (два block relay), 34027 записей
mempool, ноль привилегированных пиров, принятые CUDA shares и один
Bitcoin-процесс. Проверка: `target/core-update-audit/core32-chainwork-null-locator-live.json`.
