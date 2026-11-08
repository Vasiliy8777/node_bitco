# Первый GETHEADERS переподключённого пира

Supervisor раньше вызывал общий `requestHeaders` сразу после установки нового
соединения. Этот путь отправлял GETHEADERS через transport gate, но обходил
начальную политику preferred-пиров, счётчик начавших синхронизацию и её срок.
Аналогичная начальная отправка была в периодическом recovery-проходе для seed.
При старой вершине несколько новых соединений могли получать запросы прежде,
чем единственный допустимый источник был выбран загрузчиком.

В OutboundPeerRecoveryView выделен `requestInitialHeaders`. В рабочем
NodeRelayService его обработкой владеют начальный/live загрузчики, которые
наблюдают все READY-соединения. Supervisor не дублирует их первый запрос.
Именно загрузчик регистрирует ожидание, выбирает источник и устанавливает срок
через общий PeerManager. Базовая реализация интерфейса сохраняет прежнее
поведение для других recovery views.
Оба пути supervisor (сразу после установки и периодическая инициализация seed)
передают начальный запрос одному и тому же владельцу. Начальные уведомления
и отложенные chainwork-пробы собираются отдельно.

Отдельный `requestHeaders` проверки chainwork (после 20 минут отставания)
сохранён: это не повторная инициализация начального срока загрузки заголовков.
Ошибки транспорта и удаление закрытых пиров обрабатываются прежними путями.

`CoreHeaderTimingIntegrationTest.supervisorInitialRequestsDoNotBypassCoordinatorAdmission`
проверяет рабочий recovery view: два начальных уведомления не отправляют
самостоятельных запросов, старую вершину загружает только один источник,
удаление источника освобождает следующий; отдельный chainwork-запрос работает.
Выборочный reactor также проверяет lifecycle, relay, supervisor и chainwork
policy; журнал `target/core-update-audit/core32-initial-recovery-owner-focused.log`.
В OutboundPeerRecoveryTest дополнительно проверяются callbacks для seed,
переподключения и отдельной chainwork-пробы.

Полный scheduler Core, окно VERSION/VERACK и оставшиеся различия mempool
этим изменением не закрыты. [Общие границы синхронизации](header-sync-timing-core32.md).

Окончательный Bitcoin/RVN reactor: 5353 теста, 5341 успешный, 12 пропущенных,
без ошибок, оба приложения упакованы. Журнал
`target/core-update-audit/core32-initial-recovery-owner-full-build-final.log`,
8 ноября 2026, 15:10:06 Bangkok. Все шесть OutboundPeerRecoveryTest прошли.
После перезапуска исходная IDEA GPU-нода PID 147092: RUNNING,
height=headers=975042, IBD=false, семь пиров (два block relay), 32819 записей
mempool, ноль привилегированных пиров, принятые CUDA shares и один
Bitcoin-процесс. Проверка: `target/core-update-audit/core32-initial-recovery-owner-live.json`.
