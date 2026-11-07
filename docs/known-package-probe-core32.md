# Известные транзакции в пакетной пробе

Эталон: [Core 32.1 PreChecks и AcceptMultipleTransactionsInternal](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L1300).

Многотранзакционный testmempoolaccept больше не дедуплицирует уже известные
записи. Точное совпадение и другой witness при том же txid дают соответственно
txn-already-in-mempool и txn-same-nonwitness-data-in-mempool. В TestAcceptResult
сохраняется индекс отказавшей транзакции. RPC возвращает для неё allowed=false,
reject-reason и reject-details; остальные записи содержат только txid/wtxid,
в том числе записи перед отказавшей: пакетная проверка ещё не завершилась.

Проба не выполняет package-RBF или sibling eviction. Конфликт входов с
mempool получает Core-причину bip125-replacement-disallowed и индекс отказа.
Изолированная staged-копия сохраняет зависимости транзакций, индивидуальные
комиссии и rolling floor. submitpackage сохраняет отдельную дедупликацию
и режим CPFP/RBF; успешная проба не вставляет записи в рабочий пул.

Локальные проверки подтверждают отказ известного родителя вместо дедупликации,
индекс после новой независимой транзакции, неизменность рабочего пула и
сохранение успешного submission preflight. Нативный тест проверяет полные
RPC-ответы Core и Java для точного/изменённого witness на первой и второй
позициях пакета, а также неизменность обоих пулов.

Объём остаётся ограниченным: остальные виды частичных отказов, разделение
всех PreChecks и script checks на фазы, порядок одновременно нарушенных правил
и same-txid/different-witness дедупликация submitpackage ещё требуют доводки.
Индекс индивидуального отказа пока формируется только для перечисленных
ошибок известной транзакции и запрещённой замены.

Проверка 7 ноября 2026: полная Bitcoin/RVN-сборка завершена в 00:26:36
Bangkok: 5 284 случая, 5 272 пройдено, 12 opt-in пропущено, ошибок нет.
Нативный Core 32.1 тест выполнен, оба приложения собраны. Логи:
`target/core-update-audit/core32-known-package-focused.log`,
`target/core-update-audit/core32-known-package-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 128816. Проверено RUNNING,
height=headers=974831, IBD=false, восемь подключений (два block relay),
33 139 записей mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-known-package-live-verification.json`.
