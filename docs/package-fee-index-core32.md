# Индивидуальные комиссионные отказы пакетной пробы

Эталон: [Core 32.1 AcceptMultipleTransactionsInternal](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L1300)
и [RPC testmempoolaccept](https://github.com/bitcoin/bitcoin/blob/v32.1/src/rpc/mempool.cpp).

MempoolFeeException в многотранзакционной пробе теперь сохраняет индекс
отказавшей транзакции и числовые reject-details. RPC сообщает allowed=false,
Core reject-reason и детали только для этой записи. Остальные содержат
txid/wtxid без allowed, включая предшествующие: пакет ещё не завершил проверку.
Общая package-error для такого индивидуального отказа больше не выдаётся.

Имена комиссионных причин преобразуются общей функцией для одиночной и
индексированной пакетной пробы. Обработка остальных причин, structural
package-error, maxfeerate и submitpackage этим изменением не расширяется.

PackageProbeFeeModeTest проверяет индекс 1 для неоплаченного ребёнка и
неизменность пула. FeeFloorPriorityTest проверяет индекс 0 и числовые детали
rolling floor. Нативный BitcoinCore32CompatibilityTest сравнивает полные
ответы Core/Java для нулевой и отрицательной комиссии родителя и для отдельно
подписанного неоплаченного ребёнка. Оба пула остаются неизменными.

Объём — индивидуальные fee-проверки пакетной пробы. Общая двухфазная модель
PreChecks/scripts, приоритет остальных одновременных нарушений, структурные
ошибки графа и частичные результаты submitpackage остаются открытыми.

Проверка 7 ноября 2026: локальные сценарии и нативное сравнение полных ответов
Core 32.1 прошли. Полная Bitcoin/RVN-сборка завершена в 20:00:15 Bangkok:
5 311 случаев, 5 299 пройдено, 12 opt-in пропущено, ошибок нет;
оба приложения собраны. Логи:
`target/core-update-audit/core32-package-fee-index-focused.log`,
`target/core-update-audit/core32-package-fee-index-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 45036. Проверено RUNNING,
height=headers=974935, IBD=false, десять подключений (два block relay),
31 287 записей mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-package-fee-index-live-verification.json`.
