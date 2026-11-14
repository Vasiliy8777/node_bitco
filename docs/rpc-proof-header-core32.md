# Transaction proofs и submitheader: Core 32.1

Реализованы `gettxoutproof`, `verifytxoutproof`, `submitheader`.
Инвентарь исходного дерева: 58 имён из 176; 118 отсутствуют.
Это не закрытие всего RPC и не доказательство эквивалентности оставшихся методов.

## Transaction proofs

`MerkleBlockProof` строит и извлекает BIP37 partial Merkle tree. Формат,
порядок hashes/flags, нечётные уровни, неиспользованные байты flags,
повторяющиеся ветви, предел числа транзакций, нехватка данных и CompactSize
сопоставляются с Core. Так же, как Core, `verifytxoutproof` допускает байты
после сериализованной структуры и возвращает пустой массив при неверном root.

Поиск `gettxoutproof` использует явный blockhash, затем UTXO по txid,
затем включённый txindex. UTXO ищется prefix seek, а не обходом всей базы.
Рассмотрены pending chainstate writes и изолированное пространство snapshot.
Проверка активной цепочки и построение результата сериализованы chain monitor.
Для явно заданного side-block доказательство можно построить, но проверка
доказательства блока вне активной цепочки возвращает отказ Core.

Число транзакций сохраняется в namespace `BLOCK_TX_COUNT=0x2D` атомарно с
публикацией тела блока. Pruning сохраняет счётчик. Для старого полного блока
счётчик заполняется лениво; перед удалением старого тела миграция входит
в тот же batch. Повторный save, повторно использующий прежний payload, не
может заменить его счётчик значением из другого тела с тем же заголовком.

Ограничение миграции: у блоков, удалённых старой версией ноды до введения
счётчика, число транзакций нельзя восстановить по одному заголовку. При
отсутствии счётчика и тела проверка не доверяет значению из пользовательского
доказательства. Для полной совместимости такой старой pruned-базы требуется
восстановление метаданных из проверенных полных блоков; этот путь ещё открыт.

Нативная матрица: 646 сравнений без различий. Проверены все непустые
подмножества транзакций блоков из 3, 4 и 5 транзакций, точные proof bytes,
поиск через UTXO без txindex, повреждения, обрезание, trailing bytes,
invalidate/reconsider, создание proof side-block и реальные pruned-блоки.
Дополнительные storage-тесты проверяют restart, pruning, отменённый batch,
legacy migration и buffered UTXO writes.

## Submitheader

Метод декодирует заголовок и использует общий production-процессор headers;
он не активирует блок без тела. Проверяются наличие родителя, PoW, difficulty,
MTP/BIP94, future time, версия и failed branches. Причина отказа передаётся
от валидатора отдельным полем, без распознавания текста исключения.

Общий порядок проверки difficulty/time/version выровнен с Core. Failed
ancestor учитывается при обработке повторного заголовка и нового потомка;
непосредственного failed-флага для этого недостаточно.

30 нативных сравнений без различий: decode, отсутствующий родитель,
неверный PoW/difficulty/time/version, несколько нарушений одновременно,
повторы, trailing bytes, инвалидный предок и reconsider. Проверяется
неизменность активной высоты при приёме одного заголовка.

## Общие проверки

Схемы/help новых методов входят в общий каталог. Нативная transport/metadata/
argument матрица теперь содержит 515 сравнений без различий.
Лог: `target/core-update-audit/core32-rpc-proof-header-native.log`.

## Сборка и рабочая проверка

Полный Bitcoin reactor завершён `BUILD SUCCESS` 13 ноября 2026 в
19:03:20 +07:00. Всего 15 632 теста в 472 Surefire-отчётах:
15 620 прошли, 12 пропущены, отказов и ошибок нет. В той же сборке
прошли нативные проверки mempool/package (9 698 сравнений без различий)
и 77 сценариев блоков.

Лог: `target/core-update-audit/core32-rpc-proof-header-final-bitcoin-build.log`;
сводка: `target/core-update-audit/core32-rpc-proof-header-test-summary.json`.

Перед Maven нода штатно остановлена. После сборки запущена прежняя
IDEA-конфигурация `BitcoinNodeApplication GPU`, PID 72472, mainnet,gpu.
Работает один Bitcoin JVM, тестовые bitcoind завершены.
RPC: blocks=headers=975669, IBD=false, семь пиров при проверке.
Каталог содержит 58 методов. На рабочей ноде построен 119-байтовый proof
genesis-транзакции и проверен обратно с правильным txid. GPU — около
2546 MH/s, принятые shares после запуска.

Снимки: `target/core-update-audit/core32-rpc-proof-header-live.json`,
`target/core-update-audit/core32-rpc-proof-header-live-smoke.json`.

Полное направление RPC остаётся открытым: отсутствующие методы, кошелёк,
PSBT/descriptors, внешние signer/ZMQ и полные предметные/configuration/transport
матрицы перечислены в [общем статусе](core32-rpc-execution.md).

Источники: Core v32.1 `src/merkleblock.cpp`, `src/rpc/txoutproof.cpp`,
`src/rpc/mining.cpp`, `src/validation.cpp`; лицензия алгоритма включена
в `protocol/src/main/resources/licenses/bitcoin-core-MIT.txt`.
