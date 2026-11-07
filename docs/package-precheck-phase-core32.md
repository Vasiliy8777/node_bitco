# Все предварительные проверки пакетной пробы до скриптов

Эталон: [Core 32.1 AcceptMultipleTransactionsInternal](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L1313).

Многотранзакционный testmempoolaccept теперь откладывает исполнение скриптов
до завершения предварительных проверок всего пакета. Неисполненные записи
добавляются только в изолированную staged-копию, предоставляя входы детям.
Для каждого скрипта сохраняется проверенный снимок входов; external UTXO
повторно не читаются. После PreChecks исполняются все отложенные скрипты.
Рабочий mempool не получает этих промежуточных записей.

Обычный admit и submission не используют отложенную проверку: их вызовы
сохраняют обязательное исполнение скриптов до commit. Новый внутренний
параметр доступен только приватному пути пакетной пробы.

PackagePrecheckPhaseTest проверяет приоритет отсутствующего входа и низкой
комиссии ребёнка над невалидным witness родителя, обязательность скриптов
после успешных PreChecks, кеширование входов и неизменность рабочего пула.
Нативный BitcoinCore32CompatibilityTest портит подпись родителя и создаёт
ребёнка с его выходом и отсутствующим входом; полный RPC-ответ с отказом
ребёнка сравнивается между Core 32.1 и Java.

Это разделение script-фазы в пробе, не полная копия pipeline Core:
TRUC/cluster/ephemeral проверки ещё не все вынесены в общую фазу пакета,
ошибки самих скриптов и успешные записи перед script-отказом требуют
отдельной формы результатов, а submitpackage остаётся отдельной работой.

Проверка 7 ноября 2026: три новых сценария и нативное сравнение Core 32.1
прошли. Полная Bitcoin/RVN-сборка завершена в 20:48:45 Bangkok:
5 314 случаев, 5 302 пройдено, 12 opt-in пропущено, ошибок нет;
оба приложения собраны. Логи:
`target/core-update-audit/core32-package-precheck-phase-focused.log`,
`target/core-update-audit/core32-package-precheck-phase-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 124220. Проверено RUNNING,
height=headers=974936, IBD=false, семь подключений (два block relay),
35 889 записей mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-package-precheck-phase-live-verification.json`.
