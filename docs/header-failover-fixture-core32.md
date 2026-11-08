# Смена источника: воспроизведённый сбой тестового пира

В `NodeLifecycleServiceTest` редкий отказ после добавления параллельной
синхронизации оказался неверным предположением тестового P2P-сервера о порядке
GETHEADERS. До исправления серия из десяти переподключений дала один отказ
(`target/core-update-audit/core32-header-failover-repeat.log`).

Отладчик IDEA остановился перед проверкой второго locator в `runLifecyclePeer`:
фактический первый хеш был genesis regtest
`0f9188f13cb7b2c71f2a335e3a4fc328bf5beb436012afca590b1a11466e2206`,
ожидаемый — блок 1
`4929fd0df4a4737afb45c3a81e538ead5d496ee69d6e051d90343fcc07761303`.
Стек вёл из failover-теста в тестовый сервер. Recovery supervisor и lifecycle
waiter могут независимо построить locator до обработки первого ответа.
Повторный запрос старого locator допустим; тестовый сервер ошибочно завершался
вместо повторного ответа, лишая ноду источника заголовков/блока.

После исправления locator повторная серия выявила второе предположение той же
фикстуры: PING принимался за недопустимую замену GETDATA. Сохранённая исходная
ошибка future показала `expected: <getdata> but was: <ping>`
(`core32-header-failover-fixture-diagnostics.log`). Сервер теперь отвечает PONG
с исходным nonce и продолжает обмен заголовками/блоком.

Сервер теперь отвечает по содержимому locator: от genesis отдаёт блок 1,
от блока 1 — пустой ответ. Он допускает повторные запросы перед GETDATA, но
проверяет известный locator, сходимость (не более 16 запросов), тип и хеш
запрошенного блока. Пятисекундный предел запуска не увеличен. Production-
правила, проверка заголовков/блоков и наказание пиров этим исправлением не менялись.

Новый `lifecyclePeerAnswersRepeatedOldLocatorsAndPingBeforeBlockDownload` принудительно
отправляет два запроса от genesis, PING между ними, затем запрос от блока 1
и скачивает блок. Проверяется также nonce ответа PONG.
Он воспроизводит проблемный порядок без зависимости от планировщика потоков.

После обоих исправлений выборочный reactor прошёл: 20 исполнений класса
NodeLifecycleServiceTest (временно десять повторов failover-сценария) плюс
CoreHeaderTimingIntegrationTest, InitialHeaderRejectionTest и ManualBootstrapTest.
Ошибок нет; `target/core-update-audit/core32-header-failover-ping-focused.log`.
Временное повторение снято перед окончательной сборкой: в постоянном наборе
остаётся один детерминированный новый тест. При ожидании RUNNING исходный
отказ future тестового сервера добавляется как suppressed exception, чтобы
возможные новые ошибки фикстуры больше не скрывались общей ошибкой ожидания.

Окончательный Bitcoin/RVN reactor: 5352 теста, 5340 успешных, 12 пропущенных,
без ошибок; `target/core-update-audit/core32-header-failover-fixture-full-build.log`,
8 ноября 2026, 13:58:54 Bangkok. Исходная IDEA GPU-нода PID 143444 после
перезапуска: RUNNING, height=headers=975036, IBD=false, восемь пиров (два block
relay), 19947 mempool entries, ноль привилегированных пиров, принятые CUDA shares
и ровно один Bitcoin-процесс. Проверка:
`target/core-update-audit/core32-header-failover-fixture-live.json`.
