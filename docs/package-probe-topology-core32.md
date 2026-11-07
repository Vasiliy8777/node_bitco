# Общая форма пакета testmempoolaccept

Эталон Core 32.1: [IsWellFormedPackage и IsChildWithParentsTree](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/packages.cpp),
[PackageTestAccept и PackageChildWithParents](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L467).

Проба testmempoolaccept больше не требует форму «независимые родители и один
ребёнок». Она допускает топологически упорядоченные цепочки, ветвления и
несвязанные транзакции. Лимиты количества/веса, отсутствие дубликатов,
внутренних конфликтов и правильный порядок зависимостей сохраняются.
Для submitpackage ограничение формы child-with-parents остаётся включённым.

Дополнительно исправлена предварительная RPC-проверка submitpackage:
после разделения fee modes она вызывала testmempoolaccept и могла отклонить
допустимый CPFP/package-RBF. Новый testPackageSubmission проверяет изолированную
копию пула с правилами фактической пакетной отправки, включая CPFP и replacement.
Рабочий пул меняется только при последующем submitPackage.

PackageProbeTopologyTest проверяет три поколения, несвязанные транзакции,
ветвление без общего последнего ребёнка, отказы для неправильного порядка,
дубликатов и конфликтов, а также неизменность пула при CPFP/RBF-пробе.
MiningRpcTest проверяет маршрутизацию submitpackage через submission preflight
до relay. Нативный BitcoinCore32CompatibilityTest независимо подписывает
цепочку из трёх транзакций и две несвязанные транзакции через Core, сравнивает
allowed/txid/wtxid/vsize/fees с Java RPC и проверяет неизменность обоих пулов.

Полное совпадение частичных отказов RPC, пропуска потомков, ошибок уже
известных транзакций и всего набора причин отказа остаётся открытым.

Полная Bitcoin/RVN-сборка завершена 6 ноября 2026 в 18:17:24 Bangkok:
5 267 случаев, 5 255 пройдено, 12 opt-in пропущено, ошибок нет. Все пять
PackageProbeTopologyTest, семь MiningRpcTest и нативный Core 32.1 тест выполнены.
Оба приложения собраны. Итоговый лог:
`target/core-update-audit/core32-probe-topology-final-full-build.log`.
Промежуточный `core32-probe-topology-full-build.log` остановлен до исправления
submission preflight и не является итоговой проверкой.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с профилями mainnet/gpu, PID 120708. Проверено RUNNING,
height=headers=974803, IBD=false, девять подключений (два block relay),
27 377 транзакций mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares (2.35 GH/s в одном наблюдаемом интервале).
RPC-снимок: `target/core-update-audit/core32-probe-topology-live-verification.json`.
