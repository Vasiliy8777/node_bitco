# Вытеснение хвостового chunk без повторных обходов

Эталон: [Core 32.1 TrimToSize](https://github.com/bitcoin/bitcoin/blob/v32.1/src/txmempool.cpp#L840)
получает худший chunk и удаляет его записи напрямую.

MempoolGraphPolicy.trim больше не строит граф потомков всего пула для
каждого хвостового chunk каждого кластера. Последний chunk — суффикс
топологической линейзации: любой потомок его транзакции уже входит в этот
суффикс. Удаляемый набор не расширяется повторным поиском потомков.
Комиссия и adjusted weight берутся из уже рассчитанных сумм chunk.

EvictionTailTraversalTest сравнивает последний chunk с независимым fixed-point
обходом потомков на 300 случайных DAG, с разными весами, sigops и отрицательными
modified fees. Сравниваются состав, fee и adjusted weight. Проверка 3 000
независимых кластеров требует ровно одного прохода по записям для построения
связей при выборе одного вытеснения; проверяются удалённая транзакция и rate.

Это устранение повторных сканирований внутри одного выбора кандидата.
Повторные итерации вытеснения ещё перестраивают связи и линейзации;
постоянный TxGraph и бюджетный оптимизатор Core не реализованы. Учёт
maxPoolVirtualBytes также отличается от allocator-specific DynamicMemoryUsage
Core. Эти направления не закрываются данным исправлением.

Проверка 7 ноября 2026: mempool-набор и нативный Core 32.1 тест прошли.
Полная Bitcoin/RVN-сборка завершена в 10:43:15 Bangkok:
5 296 случаев, 5 284 пройдено, 12 opt-in пропущено, ошибок нет;
оба приложения собраны. Логи:
`target/core-update-audit/core32-eviction-tail-focused.log`,
`target/core-update-audit/core32-eviction-tail-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 32684. Проверено RUNNING,
height=headers=974886, IBD=false, девять подключений (два block relay),
25 776 записей mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-eviction-tail-live-verification.json`.
