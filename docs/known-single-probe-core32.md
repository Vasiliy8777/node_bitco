# Одиночная проба уже известной транзакции

Эталон: [Bitcoin Core 32.1 PreChecks](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L750).

Одиночный приём и testmempoolaccept различают точное совпадение wtxid
(txn-already-in-mempool) и совпадение txid при другом witness
(txn-same-nonwitness-data-in-mempool). Прежнее сообщение Transaction already
exists с txid заменено этими причинами. RPC добавляет reject-details,
совпадающее с причиной для этих двух ошибок, как Core.

KnownTransactionProbeTest проверяет неизменность пула, отсутствие чтения UTXO
для точного повтора, различие wtxid при неизменном txid и отказ до исполнения
изменённого witness. Нативный BitcoinCore32CompatibilityTest берёт реальную
транзакцию из обоих mempool, изменяет только witness и сравнивает полные
RPC-ответы для точного и изменённого вариантов. Проверяется неизменность пулов.

Объём — одиночный повтор уже известной транзакции. Дедупликация известных
записей в многотранзакционных пробах/submitpackage, порядок относительно
других одновременно нарушенных правил и полные частичные ответы остаются
отдельными направлениями проверки.

Последующая [доводка пакетной пробы](known-package-probe-core32.md) запрещает
дедупликацию в testmempoolaccept и добавляет индекс отказа для известных
транзакций. Дедупликация submitpackage остаётся отдельным поведением.

Проверка 6 ноября 2026: два новых mempool-сценария и нативный Core 32.1 тест
прошли. Полная Bitcoin/RVN-сборка завершена в 23:45:19 Bangkok:
5 282 случая, 5 270 пройдено, 12 opt-in пропущено, ошибок нет;
оба приложения собраны. Логи:
`target/core-update-audit/core32-known-probe-focused.log`,
`target/core-update-audit/core32-known-probe-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 145924. Проверено RUNNING,
height=headers=974825, IBD=false, восемь подключений (два block relay),
39 114 записей mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-known-probe-live-verification.json`.
