# Приоритет принятой dust-транзакции

Эталон: [Core 32.1 mining.cpp](https://github.com/bitcoin/bitcoin/blob/v32.1/src/rpc/mining.cpp#L495).
При стандартной политике prioritisetransaction отклоняет принятую транзакцию
с dust-выходом даже при нулевом fee_delta.

В Mempool.prioritise удалено исключение для feeDelta=0 и выровнено точное
сообщение отказа. Проверка выполняется до изменения feeDeltas и entries.
Условие касается уже принятого txid; предварительная приоритизация неизвестного
txid и последующие проверки dust-with-fee сохраняются.

Три unit-регрессии (0/+1/-1) сначала воспроизвели ошибки:
нулевой delta принимался, сообщения двух остальных отличались;
`target/core-update-audit/core32-dust-priority-red.log`.
Они проверяют неизменность detailed graph, sequence, приоритетов и spent index.

Добавлена отдельная нативная фикстура с явным acceptnonstdtxn=0. Core создаёт
и подписывает zero-fee ephemeral-dust parent и child, расходующий оба выхода.
Та же семья принимается Java Mempool с настоящими funding amount/script из Core.
Сравниваются Core RPC -8 и Java-отказ для 0/+1/-1, неизменность обоих пулов и
таблиц приоритетов. Положительный контроль +2/-1 на обычном child проверяет
совпадение итоговых fee_delta и modified_fee.

Это сравнение Core RPC с Java Mempool, а не отдельным Java RPC-сервером.
Существующая общая RPC-матрица также выполняется. Java реализует стандартную
политику; вариант Core с acceptnonstdtxn=1 этим сценарием не приравнивается к ней.
Полная матрица dust/RBF/reorg остаётся открытой.
[Остальные направления](core32-user-checklist.md).

После исправления: 67 выбранных случаев, 0 failures/errors/skips,
12 ноября 2026, 15:10:27 Bangkok;
`target/core-update-audit/core32-dust-priority-focused.log`.
Включены EphemeralDustFeeDeltaTest, NodeValidationServiceTest,
BitcoinCore32CompatibilityTest (общая RPC-матрица и новая strict-dust фикстура),
MiningRpcTest, NodeRelayServiceTest, OrdinaryRelayFeeFilterTest,
PartialPackageSubmissionTest. Полный reactor 5378 случаев повторно не запускался.

Bitcoin/RVN упакованы с `-DskipTests` после проверок, 15:12:42 Bangkok;
`target/core-update-audit/core32-dust-priority-package.log`.
Одна исходная IDEA GPU-нода PID 37916: RUNNING, blocks=headers=975530,
IBD=false, 7 пиров (2 block relay), 36511 mempool entries, 0 привилегированных
пиров, принятые CUDA shares. RPC-снимок 15:13:59:
`target/core-update-audit/core32-dust-priority-live.json`.
