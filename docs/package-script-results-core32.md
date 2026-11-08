# Результаты скриптовых отказов testmempoolaccept

Эталон: Core 32.1 `AcceptMultipleTransactionsInternal`, `PolicyScriptChecks`,
`CScriptCheck::operator()` и RPC `testmempoolaccept`. Локальные копии эталонных
исходников находятся в `target/core-update-audit/btc-validation32.cpp`,
`core32-rpc-mempool-reference.cpp` и `core32-script-error-reference.cpp`.

После общих пакетных проверок проба исполняет скрипты по порядку. При отказе
результат содержит успешно проверенный префикс, индекс отказавшей транзакции,
причину и детали входа. Последующие транзакции остаются без `allowed`.
Рабочий mempool не изменяется. Ошибка `maxfeerate` в успешном префиксе
останавливает выдачу последующих результатов, включая скриптовый отказ.
Одиночная проба также возвращает скриптовый результат вместо исключения RPC.

Диагностический путь witness v0 сохраняет конкретную причину вместо `false`:
форма/хеш witness, пустой witness, размеры script/элементов, CLEANSTACK и
EVAL_FALSE. NULLFAIL, кодирование ECDSA, VERIFY-варианты и ошибки размера стека
передают стабильные описания Core отдельно от прежних внутренних сообщений.
Существующие boolean-интерфейсы и обычный путь admission сохранены.
`reject-details` включает номер входа, txid, wtxid и предыдущий outpoint.

Проверки:

- `PackageScriptResultsTest`: отказ в каждой позиции, успешный префикс,
  подробности входа, неизменность пула, одиночная проба и обычный admission.
- `PackagePrecheckPhaseTest`: предварительные ошибки по-прежнему предшествуют
  скриптам; внешние монеты не перечитываются при исполнении скриптов.
- `BitcoinCore32CompatibilityTest`: полные RPC-ответы Core/Java при повреждённых
  подписях в каждой позиции, ограничении maxfeerate, одиночной пробе и разных
  отказах witness/P2WPKH; оба пула остаются неизменными.

Граница реализации: это не полная таблица всех ScriptError для legacy/P2SH,
Taproot/tapscript и каждого opcode. Ещё не типизированные ошибки сохраняют
внутреннее описание; их точное соответствие Core требует отдельной доводки.
Частичный submitpackage, бюджетный оптимизатор, постоянный граф и порядок
равных ставок этим изменением не закрываются.

Полный reactor Bitcoin/RVN 8 ноября 2026 завершён успешно: 5 322 случая,
5 310 успешно, 12 пропущено, ноль failures/errors. Оба приложения собраны.
Лог: `target/core-update-audit/core32-script-results-full-build.log`;
нативное сравнение с Core 32.1 прошло, включая расширенную witness-матрицу.
Исходная IDEA GPU-нода PID 77936 перезапущена: RUNNING,
blocks=headers=974978, IBD=false, семь соединений (два block relay),
23 868 записей mempool, один Bitcoin-процесс и ноль привилегированных пиров.
В журнале нового PID подтверждены принятые CUDA shares.
Снимок RPC: `target/core-update-audit/core32-script-results-live-verification.json`.
