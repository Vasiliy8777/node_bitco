# Разбор, объединение и соединение PSBT: Core 32.1

Реализованы `decodepsbt`, `combinepsbt`, `joinpsbts` в том же JVM ноды.
Разборщик и JSON-представление поддерживают PSBT 0/2, canonical CompactSize,
полные карты global/input/output, XPUB/BIP32, UTXO, подписи, preimages,
Taproot, MuSig2, proprietary и неизвестные поля. Проверяются повторные ключи,
обязательные/запрещённые поля версии, ссылки non-witness UTXO и размеры.
Повреждённые длины воспроизводят порядок чтения и точные ошибки SpanReader,
в том числе `Size of value was not the stated size`.

JSON включает fee при наличии всех UTXO и допустимых суммах, полные
script/transaction ответы и приоритет non-witness UTXO при двух источниках.
Выявленное в этих проверках различие исправлено в общем выводе транзакций:
их version показывается как uint32, как в Core 32.1.

Combiner использует идентичность unsigned transaction, а для PSBT v2 —
также правила sequence/timelock и modifiable flags. Сериализация сохраняет
порядок карт Core, пропускает поля в финализированных входах по его правилам,
упорядочивает подписи по key id и нормализует наборы Taproot leaf hashes.
Разные сериализованные XPUB/MuSig2 ключи, которые обозначают одну внутреннюю
запись Core, обрабатываются с тем же приоритетом первого значения.

Joiner работает с версией 0, проверяет повтор входов, выбирает максимальную
версию транзакции и минимальный locktime, переносит связанные метаданные,
удаляет те же поля подписи входа, что и Core, и независимо перемешивает
входы и выходы. При сравнении успешных результатов учитывается случайный
порядок, но сохраняется связь каждого элемента с его метаданными.

Нативная матрица против bitcoind 32.1:

- 7 387 случаев разбора, полных JSON-ответов и ошибок;
- 8 983 случая canonical serialization / combine;
- 6 431 случай join, из них 816 успешных;
- 45 сравнений полного HTTP RPC на main, test и regtest;
- общий каталог/help/transport/arguments — 603 сравнения.

Различий нет. Используются официальные Core PSBT vectors, BIP370 vectors,
обрезания и изменения байтов, несовместимые документы, UINT32 границы,
эквивалентные XPUB/MuSig2 записи и конфликты Taproot control blocks.
Core-векторы сопровождаются общим MIT license; BIP370 — BSD-2-Clause.

Логи: `target/core-update-audit/core32-psbt-join-native.log`,
`target/core-update-audit/core32-psbt-rpc-native.log`.

Эти методы не закрывают роли updater/signer/finalizer и wallet funding.
`analyzepsbt`, `utxoupdatepsbt`, `descriptorprocesspsbt`
и остальные отсутствующие RPC сохраняются в
[инвентаре](core32-rpc-method-inventory.md). Весь RPC остаётся незавершённым.

## Сборка и запуск

После полной сборки Bitcoin reactor на 15 635 тестах выполнена регрессия
новых изменений: весь reactor собран с выбранными RPC-тестами, BUILD SUCCESS
14 ноября 2026 в 00:03:46 +07:00. Все 29 выбранных тестов прошли без отказов,
ошибок и пропусков, включая приведённые нативные матрицы.
Лог: `target/core-update-audit/core32-rpc-psbt-bitcoin-build.log`.
Повтор полного набора несвязанных тестов этим не заявляется.

Перед Maven нода штатно остановлена. Запущена та же IDEA-конфигурация
BitcoinNodeApplication GPU, PID 111732, mainnet,gpu. Работает один Bitcoin
JVM; временные bitcoind тестов завершены. Проверены blocks=headers=975695,
IBD=false, семь соединений при проверке и принятый GPU share после запуска.
Живой каталог содержит 68 методов. Проверены PSBT 0/2, combine одинаковых
документов, join двух различных синтетических входов/выходов и вывод
version=4294967295 через decoderawtransaction. Тесты не принимают и не
рассылают транзакции.
Снимки: `target/core-update-audit/core32-rpc-psbt-live.json`,
`target/core-update-audit/core32-rpc-psbt-live-smoke.json`.

Роль Finalizer/Extractor добавлена отдельно: [реализация и проверки](rpc-psbt-finalizer-core32.md).
