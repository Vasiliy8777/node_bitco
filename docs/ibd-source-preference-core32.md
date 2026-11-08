# Предпочтение источников блоков при IBD

Core 32.1 SendMessages разрешает загрузку блоков при IBD из предпочтительного
источника, либо из обычного block-serving пира, если нет предпочтительных
соединений или глобальных блоков в полёте. ADDR_FETCH в этот запасной выбор
не входит. Вне IBD ограничение предпочтения снимается; проверки доступности
цепи, witness и limited history остаются.
Эталон: `target/core-update-audit/core32-net-processing.cpp`,
ветки sync_blocks_and_headers_from_peer и загрузки блоков около строки 6511.

Рабочая CoreBlockDownloadPeerPolicy раньше проверяла доступность цепи и сервисы,
но не учитывала это предпочтение. Теперь NodeConfiguration подключает реальный
PeerManager и глобальный признак блоков в полёте. Формула запасного источника
общая с политикой начальной загрузки заголовков. Исходящий либо NoBan пир может
быть предпочтительным при наличии block-serving сервисов; ADDR_FETCH исключён.

Допуск фиксируется в публикуемом снимке одного прохода планирования. Если
входящий пир допущен при пустой очереди, назначение ему первого блока не отменяет
остальные задания того же прохода. На следующем проходе условия вычисляются
заново. Недопущенный источник также не участвует в атрибуции window-end stalls.
Уже отправленные запросы этой проверкой не отменяются.

Новый сценарий CoreBlockDownloadPeerPolicyTest проверяет outbound, inbound,
NoBan inbound, ADDR_FETCH, limited/client сервисы, оба условия fallback,
снимок прохода и снятие ограничения после IBD. Выборочные проверки locator,
таймеров, lifecycle и Spring-конфигурации прошли:
`target/core-update-audit/core32-ibd-source-preference-focused.log`.

Связанный обход через availability GETHEADERS впоследствии устранён:
[общее состояние запросов, таймеров и проверки block IBD](availability-headers-owner-core32.md).
Полный scheduler и окно VERSION/VERACK остаются в
[списке ограничений](header-sync-timing-core32.md).

Полный Bitcoin/RVN reactor: 5360 тестов, 5348 успешных, 12 пропущенных,
без ошибок; оба приложения упакованы. Журнал
`target/core-update-audit/core32-ibd-source-preference-full-build.log`,
8 ноября 2026, 17:13:52 Bangkok. После перезапуска исходная IDEA GPU-нода
PID 29024: RUNNING, height=headers=975049, IBD=false, восемь пиров (два block
relay), 31818 записей mempool, ноль привилегированных пиров, принятые CUDA shares
и один Bitcoin-процесс. Проверка:
`target/core-update-audit/core32-ibd-source-preference-live.json`.
