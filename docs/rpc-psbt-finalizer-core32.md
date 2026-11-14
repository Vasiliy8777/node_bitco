# PSBT Finalizer/Extractor — Core 32.1

`finalizepsbt` завершает входы по имеющимся публичным данным PSBT и,
при полной готовности, извлекает сетевую транзакцию. Работает с PSBT 0/2,
legacy, P2SH, SegWit v0, Taproot key/script path, Miniscript и MuSig2.
Новые подписи и приватные ключи для этой роли не требуются.

`PsbtFinalizer` переносит порядок SignPSBTInput/ProduceSignature:
предварительная проверка финальных данных, выбор UTXO, согласование sighash,
ограничение witness-only UTXO, сохранение незавершённых входов и очистка
полей подписания завершённых входов. Существующие финальные поля проверяются
в том же порядке, включая короткий путь ProduceSignature после неудачной
первой проверки. Extractor возвращает hex только при complete=true и
extract=true; иначе возвращается канонический PSBT.

`PsbtScriptVerifier` использует единый валидатор скриптов с неизменяемой
unsigned transaction и снимком UTXO. Отсутствующий UTXO другого входа
допускается до выполнения операции подписи; Taproot-подпись требует полного
набора spent outputs, включая ANYONECANPAY, как в Core. Суммы офлайн-данных
не проходят денежные проверки приёма транзакций: отрицательная сумма
отказывает при подписи SegWit v0, но не мешает скрипту без такой операции.
Обычная проверка блоков/транзакций продолжает использовать UtxoView и
действующие проверки денежных сумм.

Miniscript: декодирование скрипта, система типов и выбор удовлетворения для
27 фрагментов (MULTI в WSH, MULTI_A в tapscript), стоимость witness,
порядок при равной стоимости, маллеабельность, preimages и timelocks.
Это не заявляет готовность текстового descriptor parser, InferDescriptor
или кошелька: соответствующие методы ещё открыты.

MuSig2: агрегация ключей/nonce, проверка каждой частичной подписи, BIP327
plain/x-only tweaks, BIP328 публичная деривация и Taproot tweak. Полученная
подпись затем проходит общий валидатор. Секретные nonce не создаются.

Нативные проверки с Bitcoin Core 32.1:

- 3200 сценариев Miniscript: 1600 WSH и 1600 tapscript; 1416 полностью
  подписанных решений, точное совпадение witness, complete, PSBT и hex.
- 2808 обычных/граничных сценариев Finalizer: legacy/P2SH, WPKH/WSH,
  Taproot key path, multisig 17/20, PSBT 0/2, шесть sighash, witness-only
  и non-witness UTXO, противоречивые финальные поля.
- 2575 документов корпуса BIP174/BIP370/Core и его мутаций: для всех документов, принимаемых
  decodepsbt, дополнительно сравниваются complete и канонический PSBT
  finalizepsbt. Включает полный MuSig2 пример Core.
- 20 сценариев граничных сумм и восемь сценариев неизвестных UTXO.
- HTTP: по 31 PSBT-сценарию на mainnet/testnet/regtest, включая новый метод,
  extract=null/false/true, неверные типы и повреждённые документы.
- 72 независимых PSBT с подписями BIP327 reference и результатами Core 32.1:
  raw/key/script paths, BIP328 paths, Taproot Merkle tweaks, три sighash,
  PSBT 0/2. Оба результата Finalizer/Extractor совпадают.
- Официальные BIP327 aggregation vectors и повреждения частичных подписей,
  сообщения, aggregate key и nonce проверяются независимо от PSBT.

Логи в `target/core-update-audit/`:
`core32-psbt-finalizer-musig-native.log`,
`core32-psbt-finalizer-edge-native.log`,
`core32-finalizepsbt-http-native.log`, `core32-musig-bip327.log`.
Предшествующие регрессии общего Script-пути: 2641 unit-тест,
нативная матрица mempool/scripts и 77 сценариев блоков.

Каталог в исходниках: 69 из 176 имён, отсутствуют 107, включая 58 wallet.
Наличие finalizepsbt не закрывает analyzepsbt, updater, descriptor processing,
signing/funding и оставшуюся RPC/transport матрицу. Полная совместимость RPC
не заявляется. Полная сборка и рабочее развёртывание подтверждены ниже; лог сборки:
`core32-rpc-finalizer-complete-bitcoin-build.log`. Предыдущий запуск
`core32-rpc-finalizer-bitcoin-build.log` был прерван и не подтверждает успешную сборку.


## Полная сборка и рабочий запуск

BUILD SUCCESS 14 ноября 2026 в 10:10:39 +07:00, 5 минут 10 секунд.
В Bitcoin reactor выполнены 15 632 теста, из них 15 620 прошли и 12 пропущены;
ошибок/падений нет, 475 suite. Текущая RPC transport/schema/help/type/arity
матрица: 611 сравнений без расхождений. Сводка:
`target/core-update-audit/core32-rpc-finalizer-test-summary.json`.

Перед Maven остановлена исходная нода; после сборки запущена та же IDEA
конфигурация BitcoinNodeApplication GPU, PID 141812, mainnet,gpu.
Blocks=headers=975739, IBD=false, семь соединений, два block-relay.
В живом каталоге 69 методов и ровно один Bitcoin JVM. Восемь живых вызовов
finalizepsbt для raw/key/script-path MuSig2 совпали с независимыми результатами
Core по PSBT и извлечённой hex-транзакции. Транзакции не принимались в mempool
и не рассылались: проверялась только офлайн-финализация.
После запуска подтверждены принятые GPU shares и около 2600 MH/s.
Снимки: `target/core-update-audit/core32-rpc-finalizer-live.json`,
`target/core-update-audit/core32-rpc-finalizer-live-smoke.json`.
