# analyzepsbt — Core 32.1

`PsbtAnalyzer` использует общий PSBT Finalizer/Script verifier. Сопоставлены
has_utxo/is_final, роли creator/updater/signer/finalizer/extractor, упорядоченные
missing pubkeys/signatures/redeemscript/witnessscript, MoneyRange, unspendable,
несовместимые locktime, fee и наличие/значение estimated_vsize/estimated_feerate.

Оценка размера использует отдельный офлайн-режим SignatureCheckMode.ESTIMATE,
эквивалентный DummySignatureCreator/Checker Core. Обычный приём блоков и
транзакций всегда использует VERIFY. Отдельный VERIFY_WITHOUT_PRECOMPUTED
воспроизводит предварительную проверку PSBT без BIP341 precomputed data:
это важно для уже финализированных Taproot witness-only входов.

Оценка сохраняет DER/pubkey/stack/script проверки, подставляет стандартный
71-byte ECDSA / 64-byte Schnorr dummy и моделирует timelock checker.
Сохраняются краткие пути Core для существующих финальных полей, порядок
sighash-проверок и ограничения witness-only UTXO. Финальная ставка комиссии
использует floor(fee*1000/vsize), включая отрицательную fee. Размер учитывает
sigop-adjusted virtual size.

Нативное сравнение: 6514 синтетических и пограничных сценариев плюс 2575
документов Core/BIP174/BIP370 — 9089 полных результатов analyzepsbt без
расхождений. Включены 72 независимых MuSig2 примера. Дополнительно прошли
2644 Script/consensus unit-теста, нативные 9698 сравнений script/package
admission и 77 сценариев блоков. Защитные unit-тесты отдельно проверяют,
что оценка не делает неверные подписи или timelock допустимыми для VERIFY.

HTTP: по 41 PSBT-сценарию на mainnet/testnet/regtest; новый метод также
включён в общую matrix schema/help/arity/types/named arguments.

Логи в `target/core-update-audit/`: `core32-analyzepsbt-native.log`,
`core32-analyzepsbt-edge-native.log`, `core32-analyzepsbt-admission-native.log`,
`core32-analysis-script-consensus.log`, `core32-analyzepsbt-http-native.log`.
Полная сборка `core32-rpc-descriptor-bitcoin-build.log`: BUILD SUCCESS,
15 643 теста, 12 пропусков, 0 ошибок/отказов, 481 набор.
Исходная конфигурация IDEA `BitcoinNodeApplication GPU` перезапущена:
PID 95088, mainnet/gpu, высота blocks=headers=975750, IBD=false.
Живая проверка всех пяти новых RPC успешна; результаты:
`core32-rpc-descriptor-live.json`, `core32-rpc-descriptor-live-smoke.json`.


Этот метод не означает завершение всего RPC. Открытый объём остаётся в
[инвентаре](core32-rpc-method-inventory.md) и
[общем плане](core32-rpc-execution.md).
