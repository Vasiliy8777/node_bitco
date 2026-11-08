# Общая фаза TRUC, cluster и ephemeral-проверок пробы

Эталон: [Core 32.1 AcceptMultipleTransactionsInternal](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L1300).

В изолированной многотранзакционной пробе предварительное добавление больше
не выполняет cluster limits и ephemeral-spends для каждой транзакции до
PreChecks следующей. После всех PreChecks выполняются общие TRUC-проверки,
cluster limits, затем ephemeral-spends; только после этого запускаются скрипты.
Индивидуальная TRUC-проверка сохраняется и видит родителей в исходном mempool,
а не временные выходы соседей по пакету. Обычный приём и submission сохраняют
свои прежние обязательные проверки.

Общие TRUC/cluster отказы помечаются как package-policy failures с причинами
TRUC-violation/too-large-cluster. Ephemeral failure сохраняет индекс транзакции.
Рабочий пул не получает предварительные записи.

PackageSharedPolicyPhaseTest проверяет приоритет позднего missing-inputs над
внутрипакетной TRUC-топологией, cluster count и ephemeral-spends, а также
сохранение отказов TRUC/cluster после успешных PreChecks и неизменность пула.
Нативный BitcoinCore32CompatibilityTest использует три поколения v3 с
намеренно нарушенными подписями и четвёртую транзакцию с отсутствующим входом:
полный ответ должен соответствовать missing-inputs до общей TRUC/script-фазы.

Это не закрывает весь mempool-объём. Полные TRUC/ephemeral диагностические
детали, ScriptError и успешный префикс при script-отказе, частичный submission,
бюджетный оптимизатор и постоянный граф остаются открытыми.
[Полный перечень исполнения](mempool-core32-execution.md).

Проверено 8 ноября 2026: полный reactor Bitcoin/RVN — 5 319 случаев,
5 307 успешно, 12 пропущено, ноль failures/errors; оба приложения собраны.
Лог: `target/core-update-audit/core32-shared-policy-phase-full-build.log`.
Нативный Core 32.1 comparison прошёл, включая новый сценарий с четырьмя
транзакциями. Исходная IDEA GPU-нода PID 131144 перезапущена и проверена:
RUNNING, blocks=headers=974973, IBD=false, шесть соединений (два block relay),
24 426 записей mempool, один Bitcoin-процесс и ноль привилегированных пиров.
RPC-снимок: `target/core-update-audit/core32-shared-policy-phase-live-verification.json`.
