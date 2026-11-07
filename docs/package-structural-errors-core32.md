# Отказы при структурной проверке пакета

Эталон Core 32.1: [IsWellFormedPackage](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/packages.cpp)
и [RPC testmempoolaccept](https://github.com/bitcoin/bitcoin/blob/v32.1/src/rpc/mempool.cpp).

PackagePolicy использует причины Core: package-too-many-transactions,
package-too-large, package-contains-duplicates, package-not-sorted,
conflict-in-package. Проверки выполняются в этом порядке. В частности,
дубликаты большого пакета не скрывают превышение веса, а ранний конфликт
входов не скрывает более позднее нарушение топологического порядка.

При структурном отказе пакет не доходит до проверки отдельных транзакций.
RPC возвращает txid, wtxid и package-error для каждой записи, без allowed.
Классификация этих ошибок выделена в TestAcceptResult.packagePolicyFailure;
обычные ошибки транзакций не получают эту классификацию.

Шесть PackageStructuralRejectionTest проверяют причины, приоритеты, отсутствие
чтения UTXO и неизменность пула. Нативный BitcoinCore32CompatibilityTest
сравнивает целые JSON-ответы Core и Java для дубликатов, неверного порядка,
двойной траты и сочетания ранней двойной траты с поздним неверным порядком.
Проверяется неизменность обоих пулов.

Объём исправления — только общая структурная проверка пакета. Частичные
результаты проверки отдельных транзакций, пропуск зависимых потомков,
известные mempool-записи и весь набор причин отказа ещё требуют доводки.
Пустые массивы и более 25 транзакций RPC по-прежнему отклоняет до проверки
пакета, как отдельную ошибку входных параметров.

Проверка 6 ноября 2026: все 559 mempool-тестов прошли. Полная Bitcoin/RVN
сборка завершена в 19:34:53 Bangkok: 5 273 случая, 5 261 пройден,
12 opt-in пропущено, ошибок нет. Нативный тест Core 32.1 выполнен,
оба приложения собраны. Логи:
`target/core-update-audit/core32-package-structural-focused.log`,
`target/core-update-audit/core32-package-structural-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с профилями mainnet/gpu, PID 60456. Проверено RUNNING,
height=headers=974809, IBD=false, пять подключений (два block relay),
28 821 транзакция mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-package-structural-live-verification.json`.
