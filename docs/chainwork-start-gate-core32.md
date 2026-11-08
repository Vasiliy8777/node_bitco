# Chainwork-таймер после фактического начала headers sync

Эталон — Core 32.1 ConsiderEviction: проверка отставания применяется к
незащищённому outbound/block-relay пиру при `state.fSyncStarted`.
Источник: сохранённый `target/core-update-audit/core32-net-processing.cpp`,
[исходник Core](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).

После разделения начальных запросов и supervisor признак `headersStarted`
в слоте означал передачу начального запроса координатору, а не его допуск.
Из-за этого chainwork-таймер мог начаться у пира, которого координатор ещё
не выбрал источником старых заголовков. Это могло привести к пробе и отключению
источника, который ещё не получил возможности выполнить начальную загрузку.

Теперь recovery view получает фактическое состояние из PeerManager. Supervisor
не выполняет chainwork-проверку, пока состояние начала не установлено. Удаление
пира и сброс начальной синхронизации отражаются тем же состоянием. Сам алгоритм
20 минут + 2 минуты, строгие границы времени, benchmark и защита пиров сохранены.
Для явно заданного нестандартного header-response-timeout остаётся прежний
режим проверки chainwork; настройка передаётся при создании relay service.

Проверки:

- OutboundPeerRecoveryTest: без допуска нет пробы/отключения даже спустя час;
  после допуска строгие 20/2-минутные границы и переподключение сохраняются.
- CoreHeaderTimingIntegrationTest: реальный recovery view различает допущенный
  и ожидающий пиры; удаление очищает признак; нестандартный тайм-аут сохраняет
  прежний режим без Core timer state.
- Проверки lifecycle и Spring-конфигурации включены в выборочный reactor:
  `target/core-update-audit/core32-chainwork-start-gate-focused.log`.

Полное совпадение scheduler и учёт предпочтения до завершения handshake всё
ещё не подтверждены; [остальной объём синхронизации](header-sync-timing-core32.md).

Полный Bitcoin/RVN reactor: 5356 тестов, 5344 успешных, 12 пропущенных,
без ошибок; оба приложения упакованы. Журнал
`target/core-update-audit/core32-chainwork-start-gate-full-build.log`,
8 ноября 2026, 16:20:00 Bangkok. После перезапуска исходная IDEA GPU-нода
PID 15448: RUNNING, height=headers=975044, IBD=false, восемь пиров (два block
relay), 35075 записей mempool, ноль привилегированных пиров, принятые CUDA shares
и ровно один Bitcoin-процесс. Проверка:
`target/core-update-audit/core32-chainwork-start-gate-live.json`.
