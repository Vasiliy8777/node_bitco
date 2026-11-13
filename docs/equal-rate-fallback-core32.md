# Равные ставки: общий порядок mempool и майнинга

Сверка Core 32.1: `btc-txmempool32.cpp`, конструктор CTxMemPool;
`core32-txgraph-reference.cpp`, CompareMainTransactions и построение
equal-feerate chunk prefix; `btc-cluster_linearize.h`.

Исправлена ошибка прежнего отчёта: production Core передаёт txid comparator,
а не integer transaction index. Integer comparator в standalone oracle задан
самим тестовым harness. Эти две вещи больше не отождествляются.

Подтверждённые различия Java были другими:

- Optimal solver сортировал отображаемый hex вместо serialized unsigned bytes.
- Межкластерный merge не учитывал вес равноставочного префикса и сравнивал
  первый/следующий txid списка вместо максимального txid chunk.
- TransactionSelector имел отдельный ordinal fallback, зависящий от порядка
  кластеров snapshot, даже после исправления graph order.

Hash256.compareRawBytes сравнивает внутренние байты без выделения hex-строк.
Общий ChunkOrderKey содержит fee/weight, equal-rate prefix weight и maximum id.
Merge и selector используют этот ключ; prefix накапливается в серии одинаковой
ставки и сбрасывается при смене ставки. Внутрикластерные зависимости сохранены.

Семь unit-регрессий сначала упали на старом коде:
`target/core-update-audit/core32-chunk-fallback-red.log`.
Они покрывают byte/display order, prefix size, maximum id, накопление/сброс
префикса, отрицательные комиссии и равноставочных siblings. Mining-регрессия
проверяет обратный snapshot order при полном бюджете и бюджете на один tx.

Полный mempool-reactor после первой правки: 3384 случая, 3383 успешных,
1 пропущен, 0 failures/errors;
`target/core-update-audit/core32-chunk-fallback-mempool.log`.
Включены 384 неизменённых optimal Core oracle случая.

Нативная RPC-матрица создаёт независимые подписанные транзакции с одинаковыми
base fee и точным weight. Порядок их txid в настоящем Core getblocktemplate
проверяется против serialized byte order, Java mempoolMiningOrder и Java GBT.
Первый прогон подтвердил graph order и выявил оставшийся ordinal selector;
`core32-chunk-fallback-focused.log`. Первый полный reactor выявил scale-test,
явно требовавший старого snapshot order; его ожидание заменено Core fallback,
проверка oversized chunk сохранена.

Это не бюджетный spanning-forest optimizer и не постоянный main/staging TxGraph.
Правила equal-rate eviction впоследствии выровнены по обратному chunk-index;
[регрессии и пределы pressure-проверки](equal-rate-eviction-core32.md).
Полная budgeted/incremental/reorg матрица остаётся открытой.
[Все направления](core32-user-checklist.md).

Полный Bitcoin/RVN reactor после исправления selector и scale-test:
5399 случаев, 5387 успешных, 12 пропущенных, 0 failures/errors;
12 ноября 2026, 15:48:59 Bangkok,
`target/core-update-audit/core32-chunk-fallback-full-build-final.log`.
Нативная RPC-матрица (два JUnit-сценария) прошла за 97.18 секунды;
в неё включена проверка реального block-template порядка. Scale-сценарий
10000 транзакций в этом прогоне занял 117.5 мс.

Одна исходная IDEA GPU-нода PID 117012: RUNNING, blocks=headers=975533,
IBD=false, 8 пиров (2 block relay), 34357 mempool entries, 0 привилегированных
пиров, принятые CUDA shares. Снимок 15:50:46:
`target/core-update-audit/core32-chunk-fallback-live.json`.
