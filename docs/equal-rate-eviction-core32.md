# Вытеснение chunk при равных ставках

Эталон Core 32.1: `core32-txgraph-reference.cpp`, ChunkOrder,
CompareMainTransactions и GetWorstMainChunk. Последний берёт rbegin() того же
chunk-index, который используется для майнинга. Следовательно, при равных
ставках удаляется больший equal-rate prefix, затем больший maximum serialized
txid chunk. Последний chunk каждого кластера является кандидатом на вытеснение.
Префикс влияет только на порядок; removed rate вычисляется по весу самого
удалённого chunk, а не всего равноставочного префикса.

В Java при равной ставке оставался первый найденный tail. Результат зависел
от порядка обхода кластеров и мог менять состав пула и rolling fee floor.
MempoolGraphPolicy.trim теперь сравнивает общий ChunkOrderKey в обратном
направлении. Вес префикса включает предшествующие chunk с той же ставкой.
Набор удаляемых txid выделяется только при выборе нового худшего кандидата.

Семь регрессий проверяют:

- Независимость singleton-eviction от двух порядков вставки.
- Выбор большего префикса при положительной и отрицательной modified fee.
- Накопленный префикс parent/child против независимого chunk.
- Отказ новому худшему chunk без удаления существующего.
- Maximum txid двухэлементного chunk вместо первого элемента.

Для весов 401/402 и комиссий 401/402 ставки fee/weight равны. Выбор Core
удаляет 402-weight chunk и даёт floor(402000/101)=3980 sat/kvB перед rolling
increment. Прежний выбор мог давать 3970. Для отрицательных комиссий проверяется
точный signed floor -3981. Существующие проверки rounding/overflow и
descendant-closure сохраняются.

До изменения: 6 failures из 7 случаев, один порядок вставки совпадал;
`target/core-update-audit/core32-equal-eviction-red.log`.
После изменения все семь прошли в полном reactor.

Это сверка comparator с исходником Core и Java-регрессии. Нативная RPC-матрица
и подписанный mining-order сценарий также проходят, но они не являются
нативным pressure-eviction тестом. Java использует virtual-byte budget вместо
Core allocator memory accounting. Budgeted/staging chunk, полный incremental
TxGraph и pressure/reorg матрица остаются открытыми.
[Общий список](core32-user-checklist.md).

Полный Bitcoin/RVN reactor: 5406 случаев, 5394 успешных, 12 пропущенных,
0 failures/errors; 12 ноября 2026, 16:12:06 Bangkok,
`target/core-update-audit/core32-equal-eviction-full-build.log`.
Семь новых случаев прошли; Native RPC/пакетный тест (два JUnit-сценария)
завершился за 104.8 секунды.

Одна исходная IDEA GPU-нода PID 47076: RUNNING, blocks=headers=975534,
IBD=false, 10 пиров (2 block relay), 35438 mempool entries, 0 привилегированных
пиров, принятые CUDA shares. RPC-снимок 16:13:36:
`target/core-update-audit/core32-equal-eviction-live.json`.
