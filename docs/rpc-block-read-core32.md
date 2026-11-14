# Чтение блоков и транзакций: Core 32.1

Добавлен `getblockstats`. Выравнены `getblockheader`, `getblock`,
`getrawtransaction`, `decoderawtransaction` и представление scriptPubKey,
scriptSig, prevout в общих ответах RPC.

`getblockstats` вычисляет выбранные и полные показатели по сохранённым
транзакциям и undo: комиссии, размеры, вес, witness, взвешенные percentiles,
расход и создание UTXO, включая особенности genesis/BIP30. Выбор отдельных
полей не обходит проверку доступности тела и undo. Ошибки выбора, типов,
hash/height и их приоритеты сопоставлены с Core.

Заголовки доступны для известных боковых ветвей и header-only блоков.
Добавлены target, difficulty, mediantime и nTx. Подтверждения и наличие
nextblockhash соответствуют положению в активной цепочке. Для getblock
verbosity выше 2 включает prevout; отрицательное значение возвращает hex.
Для getrawtransaction verbosity выше 1 добавляет fee и prevout при наличии
undo. Исправлены особый отказ для genesis coinbase, in_active_chain,
подтверждения боковой транзакции и отсутствие time/blocktime вне активной цепи.

Общий ScriptJson воспроизводит asm, тип, адрес и inferred descriptor с
контрольной суммой. Проверены Base58, Bech32/Bech32m, pubkey/multisig,
будущие witness-программы, malformed push, все однобайтовые opcodes и
DER/sighash-аннотации scriptSig. Это представление скриптов не заменяет
полноценный descriptor/miniscript parser для оставшихся RPC.

Pruning теперь может удалить genesis, как Core. Повторная инициализация
pruned-базы допускает такое состояние, проверяя сохранённую метаинформацию,
и не восстанавливает удалённое тело. Для unpruned-базы отсутствие genesis
по-прежнему считается повреждением.

Нативные проверки: 979 сравнений чтения, в том числе после invalidate/reorg
и pruning; 417 сравнений script/decoderawtransaction на каждой из трёх
сетей main, test, regtest — всего 1 251. Различий нет.
Логи: `target/core-update-audit/core32-rpc-creators-transport.log`,
`target/core-update-audit/core32-rpc-tx-creators-native.log`.

Полное закрытие RPC этим не подтверждено. В частности, сохраняется описанная
в [transaction proofs](rpc-proof-header-core32.md) необходимость восстановления
nTx для тел, удалённых старой версией до введения соответствующего индекса.
Исторические mainnet BIP30-блоки не входят в нативную regtest-матрицу.
