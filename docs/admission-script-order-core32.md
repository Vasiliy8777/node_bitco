# Комиссии и RBF до исполнения скриптов

Эталон: [Core 32.1 PreChecks, ReplacementChecks и PolicyScriptChecks](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp).

MempoolValidator разделён на предварительные контекстные/input-policy проверки
и исполнение скриптов. Публичный validate по-прежнему выполняет обе части.
Mempool.admitInternal сначала проверяет fee/minrelay/rolling floor, RBF и
перспективный граф, затем исполняет скрипты до любой записи в рабочий пул.
И быстрый бесконфликтный путь, и путь через candidate-копию требуют успешного
исполнения скриптов до commit. Capacity trim также выполняется после скриптов.

AdmissionScriptOrderTest проверяет приоритет minrelay и RBF fee над
невалидным witness, обязательный script validation при достаточной комиссии,
неизменность пула и сохранение полной проверки публичного MempoolValidator.
Нативный BitcoinCore32CompatibilityTest портит подпись нулевой по комиссии
транзакции, сохраняя структуру witness: Core и Java отклоняют её по комиссии,
а не при исполнении скрипта. Тексты этих ошибок пока различаются — этот тест
сравнивает приоритет, а не полное равенство RPC-ответов.

Объём — порядок исполнения скриптов при admission. Разделение всего пакета
на общие PreChecks и отдельную script-фазу, приоритет всех одновременных
нарушений, соответствие всех текстов ошибок и полный пакетный pipeline Core
ещё требуют доводки. Consensus-проверка блоков этим изменением не меняется.

Проверка 7 ноября 2026: три новых сценария и нативный Core 32.1 тест прошли.
Полная Bitcoin/RVN-сборка завершена в 17:32:51 Bangkok:
5 301 случай, 5 289 пройдено, 12 opt-in пропущено, ошибок нет;
оба приложения собраны. Логи:
`target/core-update-audit/core32-script-order-focused.log`,
`target/core-update-audit/core32-script-order-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 60556. Проверено RUNNING,
height=headers=974921, IBD=false, десять подключений (два block relay),
27 147 записей mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-script-order-live-verification.json`.
