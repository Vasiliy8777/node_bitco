# Availability GETHEADERS: общее состояние синхронизации

CoreBlockDownloadPeerPolicy.refresh раньше отправлял собственные GETHEADERS
напрямую через sendAsync. Transport gate был общим, но обходились выбор
начального источника, счётчик начала, locator и начальный срок загрузки.
Кроме того, отложенная попытка могла сама устанавливать ещё две минуты
локального ожидания, хотя реального запроса не было.

В стандартном режиме refresh теперь использует CoreAvailabilityHeaders и тот
же CoreHeadersResponseControl/PeerManager, что начальная и live синхронизация:

- первый запрос учитывает preferred/fallback и число начавших синхронизацию;
- начальный locator и срок формируются общей политикой;
- успешная постановка запроса в очередь возвращает boolean через
  trySendGetHeadersAsync; сетевой writer не блокирует refresh;
- отказ transport gate или начального допуска не устанавливает новый cooldown;
- исходный срок проверяется на каждом refresh, даже между availability-пробами;
- обычный timeout закрывает пира; NoBan сбрасывает состояние и допускает
  последующее начало без выдуманного подтверждения ответа.

Проверка доступности остаётся активной во время block IBD: initial waiters уже
могут завершиться, а live-синхронизация ещё не началась. Ответы принимает прежний
общий dispatcher/relay, поэтому смена источника не требует второго reader.
Явно заданный нестандартный header-response-timeout сохраняет прежний режим.

Отдельная неблокирующая резервация отправителя на пира предотвращает гонку
initial/live/availability: второй producer не может отправить пакет между
резервированием первого запроса и его откатом. Резервация освобождается также
при ошибке и удалении соединения.

Проверки: отложенный допуск и немедленный повтор; смена источника; обычный/NoBan
timeout с управляемыми часами во время cooldown; конкурентная незавершённая
отправка; общий gate и получение async-ответа синхронным waiter на настоящем
локальном P2P-соединении. Существующий NodeLifecycleServiceTest проверяет
переподключение и принятие блока во время IBD. Выборочный reactor прошёл:
`target/core-update-audit/core32-availability-owner-focused.log`.

Это закрывает конкретный обход начальной политики через availability refresh.
Периодичность всего Java scheduler, полный SendMessages и окно VERSION/VERACK
по-прежнему не объявляются эквивалентными Core;
[оставшиеся границы](header-sync-timing-core32.md).
Последующее уточнение отделяет внутренние ошибки locator от транспортных
сбоев и гонки socket close;
[проверки и поведение](availability-failure-boundary-core32.md).

Полный Bitcoin/RVN reactor: 5364 теста, 5352 успешных, 12 пропущенных,
без ошибок; оба приложения упакованы. Журнал
`target/core-update-audit/core32-availability-owner-full-build.log`,
8 ноября 2026, 20:02:26 Bangkok. Все шесть реальных P2P gate/ownership-тестов
и сценарий возобновления block IBD прошли.
После перезапуска исходная IDEA GPU-нода PID 29204: RUNNING,
height=headers=975064, IBD=false, пять пиров (два block relay), 30877 записей
mempool, ноль привилегированных пиров, принятые CUDA shares и один
Bitcoin-процесс. Проверка: `target/core-update-audit/core32-availability-owner-live.json`.
