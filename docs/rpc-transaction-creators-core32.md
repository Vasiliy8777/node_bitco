# Создание транзакций и PSBT: Core 32.1

Реализованы `createrawtransaction`, `createpsbt` и `converttopsbt` внутри
существующей ноды. Они создают неподписанный результат, не принимают
транзакцию в mempool и не передают её сети.

Поддержаны версии транзакций 1–3, locktime, replaceable и явные sequence;
порядок проверки параметров соответствует Core. Outputs принимаются как
объект или массив пар; сохраняется порядок, распознаются повторные JSON-ключи
и повтор одного адреса в разных написаниях. Суммы проходят общую fixed-point
проверку. Адреса проверяются для выбранной сети, включая различающиеся
правила пробелов Base58 и Bech32. Data сериализуется с нужной длиной push.

PSBT Creator реализован для BIP174 версии 0 и BIP370 версии 2. Как в Core
32.1, по умолчанию используется версия 2. В ней сохраняются global version,
fallback locktime, CompactSize input/output counts, outpoints, sequence,
amount и scripts. Для версии 0 сохраняется unsigned legacy transaction.
Преобразование проверяет режим witness и наличие scriptSig/witness; при
permitsigdata=true удаляет их до формирования PSBT.

Нативная матрица сравнивает полные ответы и ошибки с bitcoind 32.1:
6 857 случаев на каждой из сетей main, test, regtest — всего 20 571,
различий нет. Включены неверные типы/диапазоны, несколько ошибок одновременно,
PSBT 0/2 и неверные версии, witness, повторные JSON-ключи, data до 65 536
байт и граница CompactSize на 253 входах/выходах. Общая матрица
transport/metadata/arguments — 556 сравнений без различий.

Логи: `target/core-update-audit/core32-rpc-tx-creators-native.log`,
`target/core-update-audit/core32-rpc-creators-transport.log`.

Эти три метода не заменяют оставшиеся роли PSBT: parsing, updating, signing,
combining, finalizing, extracting и wallet funding. Соответствующие методы
остаются в [инвентаре](core32-rpc-method-inventory.md); весь RPC ещё открыт.
