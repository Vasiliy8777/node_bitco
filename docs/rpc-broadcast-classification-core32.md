# sendrawtransaction: отсутствующие входы и подтверждённые выходы

Эталон Core 32.1: `core32-node-transaction-reference.cpp`,
HandleATMPError/BroadcastTransaction, и `core32-rpc-util-reference.cpp`,
RPCErrorFromTransactionError.

Отказ MissingInput теперь передаёт явный признак через
MempoolAdmissionException.isMissingInputs(), без разбора строки исключения.
sendrawtransaction преобразует его в RPC -25 с
`bad-txns-inputs-missingorspent`. Прочие ошибки admission сохраняют -26.
Раньше отсутствие UTXO возвращало -26 и внутреннее сообщение об OutPoint.

После ранних burn/decode/fee-проверок, до mempool/cap/приёма проверяется наличие
хотя бы одного выхода транзакции в цепном UTXO. Наличие даёт -27,
`Transaction outputs already in utxo set`, как в Core. Это не исторический поиск
txid: если все выходы потрачены цепью, этот ранний отказ не гарантируется.
Наложение mempool намеренно не используется: наличие выходов в цепном UTXO
не зависит от неподтверждённых трат. Поиск выполняется под chain monitor,
txid вычисляется один раз для всех выходов.

Нативная матрица добавляет девять сравнений полных error-объектов:
неизвестный prevout, существующий txid с несуществующим индексом выхода,
подтверждённая coinbase. Каждый случай проверяется с maxfeerate=0, tiny cap
и обычным cap. У Core и Java одинаковые подтверждённые блоки из фикстуры.

Это не полная матрица ошибок транзакций. Все потраченные выходы, конкурирующие
reorg/eviction, MEMPOOL_ERROR и полная таблица script/debug
отказов остаются отдельным объёмом.
[Оставшиеся направления](core32-user-checklist.md).
Окно раздельных chain-захватов между локальными проверками и admission
впоследствии закрыто [атомарным broadcast](atomic-broadcast-core32.md).

Первоначальный прогон выявил отличие точного текста -27 от старой формулировки:
Core 32.1 возвращает `Transaction outputs already in utxo set`.
Сообщение исправлено по нативному ответу; код и UTXO-условие сохранились.

Выборочная проверка завершена 12 ноября 2026, 02:12:47 Bangkok:
38 случаев, 0 failures/errors/skips, включая BitcoinCore32CompatibilityTest,
MiningRpcTest, NodeRelayServiceTest, PartialPackageSubmissionTest.
Журнал: `target/core-update-audit/core32-sendraw-classification-focused-final.log`.
Полный reactor 5378 случаев для этого изменения повторно не запускался.

Bitcoin/RVN упакованы с `-DskipTests` после проверок, 02:13:34 Bangkok;
`target/core-update-audit/core32-sendraw-classification-package.log`.
Одна исходная IDEA GPU-нода PID 8660: RUNNING, blocks=headers=975466,
IBD=false, 7 пиров (2 block relay), 41518 mempool entries, 0 привилегированных
пиров; журнал подтверждает принятые CUDA shares. Снимок 02:14:12:
`target/core-update-audit/core32-sendraw-classification-live.json`.
