# Атомарный sendrawtransaction: проверка и приём

Core BroadcastTransaction удерживает cs_main при проверке цепного UTXO,
поиске принятого txid, пробе клиентского cap и приёме. В Java эти действия
выполнялись отдельными вызовами с отдельными захватами chain monitor. Между
ними могли измениться цепь, fee delta или присутствие транзакции в mempool.
Например, известная запись могла исчезнуть после решения пропустить cap,
и последующий обычный приём уже не проверял клиентский лимит.

Новая NodeValidationService.admitForBroadcast удерживает один chain monitor
от synchronize/expire и UTXO-проверки до cap, admission и регистрации
unbroadcast. Вложенные существующие методы используют тот же reentrant monitor.
Результат содержит принятую запись и признак нового приёма; известный txid
возвращает сохранённый witness без новой регистрации.

Relay больше не выполняет второй самостоятельный поиск/приём. Он объявляет
возвращённую принятую транзакцию после выхода из monitor и рассматривает
orphans только для нового приёма. RPC декодирует и проверяет burn/формат fee
до операции, затем переводит типизированные причины CONFIRMED/MAX_FEE и
MissingInput в прежние проверенные Core-коды и сообщения.

Регрессия NodeValidationServiceTest удерживает пробу на latch и запускает
конкурирующий prioritisetransaction. ThreadMXBean проверяет BLOCKED,
identity chain monitor и владеющий им поток. Дополнительные spy-проверки
подтверждают удержание внешнего monitor при входе в admission и регистрацию
unbroadcast. После освобождения проверяются принятая запись, отложенная fee
delta и повтор известного txid с tiny cap. Тест не использует увеличение
production-тайм-аутов для воспроизведения гонки.

Существующая нативная RPC/пакетная матрица проверяет неизменность внешнего
поведения, включая ошибки, ceil/base fee, обе fee delta, повтор witness и
подтверждённые выходы. OrdinaryRelayFeeFilterTest обновлён под результат нового
API; первоначальный прогон выявил незаданный mock, а не ослабление feefilter.

Закрыто окно раздельных chain-захватов именно локального broadcast. Полная
матрица reorg/eviction/источников и весь SendMessages scheduler остаются
отдельными направлениями. [Общий список](core32-user-checklist.md).
Проба впоследствии выделена в [типизированный broadcast preflight](broadcast-preflight-core32.md),
чтобы первый отказ завершал обработку без повторного admission.

Финальная выборочная проверка: 52 случая, 0 failures/errors/skips,
12 ноября 2026, 03:41:52 Bangkok;
`target/core-update-audit/core32-atomic-broadcast-focused-final.log`.
Включены NodeValidationServiceTest, BitcoinCore32CompatibilityTest,
MiningRpcTest, NodeRelayServiceTest, OrdinaryRelayFeeFilterTest,
PartialPackageSubmissionTest. Это не новый полный reactor 5378 случаев.

Bitcoin/RVN упакованы с `-DskipTests` после проверок, 03:42:34 Bangkok;
`target/core-update-audit/core32-atomic-broadcast-package.log`.
Одна исходная IDEA GPU-нода PID 77932: RUNNING, blocks=headers=975472,
IBD=false, 3 пира (2 block relay), 42889 mempool entries, 0 привилегированных
пиров, принятые CUDA shares. RPC-снимок 03:43:10:
`target/core-update-audit/core32-atomic-broadcast-live.json`.
