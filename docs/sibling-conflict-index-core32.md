# TRUC sibling eviction через spent index

Эталон: [Core 32.1 SingleTRUCChecks](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/truc_policy.cpp).
Sibling eviction допускается при одном потомке родителя и ancestor count
ребёнка, равном двум; после reorg дополнительные дети, внуки или предки
не должны ошибочно давать это исключение.

MempoolGraphPolicy.addSiblingConflict использует поддерживаемый spent index:
проверяет траты выходов родителя, наличие ровно одного уникального ребёнка,
отсутствие трат выходов ребёнка и его ancestry. Построение связей всего пула
для подсчёта этой семьи убрано. Несколько входов одного ребёнка не считаются
несколькими детьми. Индекс передаётся из того же синхронизированного mempool.

SiblingConflictIndexTest сравнивает кандидата с прежним descendant/ancestor
алгоритмом на 500 DAG с несколькими детьми, дополнительными предками,
внуками и смешанными версиями, возможными после reorg. Синтетические графы
не имеют конфликтующих трат выходов. Тест с 10 000 несвязанными записями
запрещает entrySet-обход и проверяет нахождение допустимого sibling.

Объём — выбор sibling-кандидата. Проверки комиссии, диаграммы, версии и
топологии при окончательном приёме сохранены. Это не замена TxGraph или
бюджетного оптимизатора Core. Случайное сравнение с прежним алгоритмом не
является полной нативной матрицей TRUC после reorg.

Проверка 7 ноября 2026: mempool-набор и нативный Core 32.1 тест прошли.
Полная Bitcoin/RVN-сборка завершена в 15:02:54 Bangkok:
5 298 случаев, 5 286 пройдено, 12 opt-in пропущено, ошибок нет;
оба приложения собраны. Логи:
`target/core-update-audit/core32-sibling-index-focused.log`,
`target/core-update-audit/core32-sibling-index-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 56836. Проверено RUNNING,
height=headers=974905, IBD=false, девять подключений (два block relay),
37 532 записи mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-sibling-index-live-verification.json`.
