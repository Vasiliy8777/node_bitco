# Multisig и подписи сообщений: Core 32.1

Реализованы `createmultisig`, `signmessagewithprivkey`, `verifymessage`.
Методы не создают кошелёк и не импортируют ключи в постоянное хранилище.

Multisig поддерживает legacy, p2sh-segwit и bech32, порог и до 20 ключей,
лимит redeemScript 520 байт для legacy, fallback при uncompressed/hybrid
ключах, warnings, descriptor и checksum. Порядок проверки порога, списка
ключей, адресного типа и размера сопоставлен с Core. Общий ScriptJson также
распознаёт minimally pushed числовые пороги/счётчики 17–20, допускаемые Solver.

Подписи сообщений используют Bitcoin Signed Message domain, CompactSize и
двойной SHA256, WIF выбранной сети и детерминированный ECDSA с low-S.
65-байтовая подпись сохраняет recovery id и признак сжатого ключа.
Проверка восстанавливает публичный ключ, сверяет PKHash и точно воспроизводит
ошибки адреса и Base64. Проверены все 256 значений заголовка compact signature:
Core использует младшие биты header-27 и допускает эквивалентные значения
вне диапазона, создаваемого SignCompact. Самостоятельный запрет таких значений
давал бы другое поведение.

Нативная сверка на main, test и regtest:

- 3 314 случая createmultisig на сеть — всего 9 942;
- 629 случаев подписи/проверки сообщений на сеть — всего 1 887;
- 457 случаев общего script/decode на сеть — всего 1 371;
- 7 017 случаев transaction/PSBT Creator на сеть — всего 21 051;
- 582 transport/metadata/argument, 646 proof, 30 submitheader и 979 block read.

Все перечисленные сравнения завершились без различий. Для подписей
используются только известные искусственные ключи 1, 2, 17 и n-1.
Включены Unicode/NUL/длинные сообщения, high-S подписи, padding и запрещённые
символы Base64, длины signature, неверные WIF и приоритеты ошибок.

Лог: `target/core-update-audit/core32-rpc-utility-native.log`.
Полный RPC остаётся открыт; список отсутствующих методов и границы проверки
существующих методов сохраняются в [инвентаре](core32-rpc-method-inventory.md)
и [журнале выполнения](core32-rpc-execution.md).

## Сборка и запуск

Полный Bitcoin reactor до изменений multisig/message завершился BUILD SUCCESS
13 ноября 2026 в 23:12:38 +07:00: 15 635 тестов, 15 623 прошли,
12 пропущены, ошибок и отказов нет (473 Surefire suite).
Лог: `target/core-update-audit/core32-rpc-creators-bitcoin-build.log`;
сводка: `target/core-update-audit/core32-rpc-creators-test-summary.json`.

После добавления multisig/message выполнена целевая регрессия и сборка
всего Bitcoin reactor: BUILD SUCCESS в 23:23:28 +07:00, 28 тестов без
отказов/ошибок/пропусков, включая все приведённые нативные матрицы и
MiningRpc, chain-wait, connection-limit, max-fee проверки. Повтор полного
набора несвязанных тестов этим не заявляется.
Лог: `target/core-update-audit/core32-rpc-utility-bitcoin-build.log`.

Перед каждой компиляцией исходная нода штатно остановлена, затем запущена
прежняя IDEA-конфигурация BitcoinNodeApplication GPU. Текущий PID 44056,
mainnet,gpu; blocks=headers=975693, IBD=false, девять соединений при проверке.
Живой RPC содержит 65 методов; выполнены создание bech32 multisig и подпись/
проверка сообщения известным искусственным ключом 1. Проверки не импортируют
ключ и не создают/расходуют UTXO.
Снимки: `target/core-update-audit/core32-rpc-utility-live.json`,
`target/core-update-audit/core32-rpc-utility-live-smoke.json`.
