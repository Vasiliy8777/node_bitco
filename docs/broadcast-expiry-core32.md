# Broadcast: ранний отказ не запускает expiry настоящего mempool

В Core 32.1 BroadcastTransaction (`core32-node-transaction-reference.cpp`)
ранние confirmed/known результаты и test_accept/client cap не запускают
expiry настоящего mempool. Отклонённый client cap не является новым приёмом.
В `btc-validation32.cpp` AcceptSingleTransaction также возвращается при
args.m_test_accept до FinalizeSubpackage и LimitMempoolSize.

В Java admitForBroadcast вызывал expirePersistent до этих проверок. Отказ
новой или заменяющей транзакции мог удалить старые записи и потомков, изменить
sequence/spent index и unbroadcast. Для повторного известного txid expiry
также превращал быстрый повтор в новый admission и иной клиентский результат.

Ранний expirePersistent удалён из этой операции. Настоящий expiry при обычном
новом admission и существующих операциях обслуживания сохранён. Проба по-прежнему
работает на изолированной копии; chain monitor охватывает все broadcast-фазы.

Регрессия использует управляемый Clock и expiry=10 секунд. В настоящий pool
добавляются parent/child с unbroadcast; время сдвигается на 20 секунд без
события приёма. Проверяются четыре ранних результата:

- RBF с достаточной replacement fee, отклонённый только по tiny client cap.
- Повтор принятого parent при tiny cap.
- Найденный выход в цепном UTXO.
- Отсутствующий вход новой транзакции.

Сравниваются entries, весь detailed graph snapshot, mutation sequence,
unbroadcast, rolling minimum и владельцы обоих spent-outpoint индексов.
В конце явный expire обязан удалить обе старые записи, подтверждая, что
фикстура действительно достигла порога и очистка не отключена.

До исправления все четыре случая воспроизвели расхождение:
3 assertion failures и 1 error повторного admission;
`target/core-update-audit/core32-broadcast-expiry-red.log`.
Это Java clock-регрессия плюс сверка порядка с исходником Core. Существующая
нативная RPC/пакетная матрица отдельно проверяет обычные wire/RPC результаты;
она не воспроизводит этот expiry-сдвиг часов у Core.

Полная матрица expiry при block/reorg/package/eviction и фактический бюджетный
оптимизатор остаются открытыми. [Общий список](core32-user-checklist.md).

После исправления: 59 выбранных случаев, 0 failures/errors/skips,
12 ноября 2026, 14:26:41 Bangkok;
`target/core-update-audit/core32-broadcast-expiry-focused.log`.
Включены NodeValidationServiceTest, BitcoinCore32CompatibilityTest,
MiningRpcTest, NodeRelayServiceTest, OrdinaryRelayFeeFilterTest,
PartialPackageSubmissionTest. Это не новый полный reactor 5378 случаев.

Bitcoin/RVN упакованы с `-DskipTests` после проверок, 14:27:44 Bangkok;
`target/core-update-audit/core32-broadcast-expiry-package.log`.
Одна исходная IDEA GPU-нода PID 82088: RUNNING, blocks=headers=975528,
IBD=false, 7 пиров (2 block relay), 36850 mempool entries, 0 привилегированных
пиров, принятые CUDA shares. RPC-снимок 14:28:58:
`target/core-update-audit/core32-broadcast-expiry-live.json`.
