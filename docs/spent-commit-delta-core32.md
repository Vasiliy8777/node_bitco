# Обновление spent-index при commit по разнице

RBF/eviction, пакетный приём и финальный reorg commit больше не пересобирают
spent-index по inputs всех записей. Новый commitEntries сравнивает исходный
и уже проверенный набор txid, удаляет входы исчезнувших транзакций, затем
добавляет входы новых. Такой порядок сохраняет нового владельца общего входа
при замене. Для прежнего txid inputs неизменны; обновлённые weight/sigops
учитываются отдельно в totalVirtualBytes. Арифметические проверки предшествуют
изменению карт, графа и unbroadcast.

Перестановка entries в топологический порядок при подготовке reorg не меняет
ни записи, ни связи, поэтому прежний повторный rebuildSpent после сортировки
также удалён. Revalidate продолжает выполнять реальную проверку транзакций и
копирует готовый spent-index из проверенного replacement-пула; его поведение
не подменено обновлением непроверенного набора.

Публичная регрессия: admitPackage с уже известной транзакцией не должна
перечитывать inputs независимой записи пула. До изменения она воспроизвела
обход из rebuildSpent (`core32-spent-commit-delta-red.log`), после проходит,
сохраняя результат и исходный detailedSnapshot/transaction-set sequence.

MempoolCommitIndexTest проверяет внутреннюю границу публикации независимо от
policy/scripts: 160 случайных наборов и порядков сверяются с заново построенным
spent-index, суммой virtualSize и некешированной диаграммой. Дополнительно
проверяются замена владельца входа, очистка входов child и unbroadcast, пустой
commit, независимость карты candidate, изменение sigops/fee при том же txid и
отказ по переполнению без изменения состояния. Приватная граница вызывается
через reflection; отдельная публичная регрессия проверяет её реальный путь.
Целевой прогон: `target/core-update-audit/core32-spent-commit-delta-focused.log`.

Граница реализации: карты candidate/entries и ключи графа ещё обходятся целиком.
Изменяемые staged-индексы копируются O(V+E), отдельные policy/RBF-проверки могут
строить свои графы. Полный разделяемый Core main/staging TxGraph,
инкрементальные линейзации и бюджетный оптимизатор остаются открытыми.

Полный Bitcoin/RVN reactor: 5422 случая, 5410 успешных, 12 пропущенных,
0 failures/errors; оба приложения упакованы 12 ноября 2026, 21:25:37 Bangkok.
Лог: `target/core-update-audit/core32-spent-commit-delta-full-build.log`.
Оба нативных Core-сценария прошли за 112,8 секунды; все 11 тестов
NodeLifecycleServiceTest также прошли.

Исходная IDEA GPU-нода PID 36316, RUNNING с 21:26:25. RPC-снимок 21:26:41:
blocks=headers=975562, IBD=false, 8 пиров (2 block relay), 35345 mempool entries,
0 привилегированных пиров. Проверен один Bitcoin-процесс, тестовых bitcoind
не осталось. CUDA accepted=5 в 21:26:41.
Снимок: `target/core-update-audit/core32-spent-commit-delta-live.json`.
