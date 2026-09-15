# Проверки консенсуса

Изменения проверены локальными тестами на JDK 21. Эта карта описывает реализованные
проверки и покрытие; она не является подтверждением полной совместимости с Bitcoin Core.

| Область | Реализация | Тесты |
| --- | --- | --- |
| Witness commitment, reserved value, последний подходящий выход coinbase, запрет witness без commitment | `WitnessCommitmentValidator` | `WitnessCommitmentValidatorTest`, `BlockProcessorTest` |
| Лимит sigops, legacy/P2SH/witness v0 | `SigOpCounter`, `TransactionSigOpCost`, `BlockSigOpsValidator` | Одноимённые тесты, `BlockConnectChangesBuilderTest` |
| Schnorr BIP340 | `SchnorrSignature`, `TaggedHash` | `SchnorrSignatureTest`: 19 официальных векторов |
| Taproot sighash BIP341 | `TaprootSignatureHash` | `TaprootSignatureHashTest`: 7 официальных примеров key-path |
| Key-path, script-path, control block, annex, OP_SUCCESS, CHECKSIGADD, CODESEPARATOR, бюджет подписей | `TaprootValidator`, `TaprootExecutionData`, `ScriptInterpreter` | `TaprootValidatorTest`, `InputScriptValidatorTaprootTest` |
| Активация Taproot по предкам конкретной ветки | `TaprootDeployment`, `ConsensusScriptFlags` | `TaprootDeploymentTest` |
| Signet challenge и виртуальные транзакции | `SignetBlockValidator` | `SignetBlockValidatorTest`: подписанный пример, изменение времени, ошибочный solution |
| Беззнаковая версия транзакции в BIP68/CSV | `SequenceLocks`, `BlockConnectChangesBuilder`, `ScriptInterpreter` | `SequenceLocksTest`, `CheckSequenceVerifyTest`, `BlockConnectChangesBuilderTest` |
| Finality coinbase | `BlockConnectChangesBuilder` | `BlockConnectChangesBuilderTest` |
| Исторический lax DER и применение BIP66 флагами | `LegacyDerSignatureReader`, `ScriptInterpreter` | `LegacyDerSignatureReaderTest`, `InputScriptValidatorTest` |
| Размеры коллекций при разборе и лишняя witness-сериализация | `BitcoinReader`, `TransactionParser`, `BlockParser` | `ParserLimitsTest` |

`ContextualTransactionValidator.validateInputs` проверяет входы, суммы и maturity без
исполнения скриптов. При подключении блока скрипты исполняются с контекстными флагами
один раз, после этих проверок.

`BlockProcessor` проверяет структуру тела, witness commitment и signet challenge до
сохранения нового блока. Для повторно полученного активного блока тело также проверяется:
совпадение заголовка и txid не подтверждает корректность witness.
Контекстные проверки сохранённой боковой ветки выполняются при попытке её активации.
Статус `STORED_SIDE_CHAIN_CONTEXT_PENDING` поэтому не означает полную валидность блока.

Тест `rejectsInvalidTaprootSignatureWithValidCommitmentWithoutChangingUtxos` проходит
через `BlockProcessor` и RocksDB: при ошибочной подписи остаются прежними UTXO и вершина,
выходы отклонённого блока и undo-запись не появляются. Исходный UTXO в этом тесте
задаётся фикстурой; тест не заменяет начальную синхронизацию цепи.

## Запуск

Дополнительно закрыт переход к legacy-проверке для некорректного P2SH-wrapped
witness `scriptSig`. `P2shWitnessWrapperRegressionTest` проверяет PUSHDATA1/2/4,
лишний push, поведение до активации WITNESS и корректную обёртку неизвестной версии.
`BlockProcessorTest.rejectsMalformedWrappedWitnessWithoutChangingChainState`
проверяет отказ через приём блока с сохранением UTXO и вершины цепи.

Mempool теперь проверяет absolute finality и BIP68 относительно следующего блока.
Добавлены индекс расходуемых OutPoint, атомарный отказ при конфликте, поддержка
неподтверждённых родителей и удаление потомков при исключении родителя.
`MempoolContextTest` покрывает границы времени/высоты, конкурентные траты,
повторную валидацию и сохранение состояния при ошибке чтения истории.

API `admit(transaction, height, view)` заменён на
`admit(transaction, MempoolValidationContext, view)`: без MTP и истории входов
полная проверка временных ограничений невозможна. Пример для вызывающего слоя:

```java
synchronized (chainState) {
    var tip = chainState.activeTip();
    var resolver = new BlockIndexMedianTimePastResolver(tip, lookup);
    var context = new MempoolValidationContext(
        Math.addExact(tip.height(), 1),
        MedianTimePast.calculate(tip, lookup),
        resolver::resolvePreviousMedianTimePast);
    mempool.admit(transaction, context, chainUtxoView);
}
```

После изменения активной цепи вызывающий слой должен вызвать
`mempool.revalidate(context, chainUtxoView, confirmedTxIds)` под той же блокировкой.
UTXO view должен соответствовать новой вершине. Подтверждённые родители удаляются,
а их дети проверяются по новым UTXO. Эта операция не возвращает автоматически
транзакции из отключённых блоков — их нужно повторно подать через `admit` в порядке
зависимостей. Эти действия теперь автоматически выполняет `NodeValidationService`:
`processBlock`, `admit` и `mempoolEntries` используют общую блокировку цепи.
При реорганизации сервис повторно подаёт транзакции отключённых блоков и их детей.
`NodeValidationServiceTest` проверяет этот сценарий на RocksDB и mined regtest-блоках.
RPC/P2P обработчики должны обращаться к этому сервису; сетевые обработчики ещё не реализованы.

`NodeConfiguration` создаёт базу и сервис при заданном `bitcoin.data-directory`.
Закрытие Spring context закрывает RocksDB. Сеть задаётся `bitcoin.network` (по умолчанию regtest).

## Дополнительные исправления Script и политики

- `CHECKMULTISIG` обходит подписи и ключи от вершины стека, как Core. Пустая подпись
  не отменяет проверки кодировки ключа. `CHECKSIG` также сохраняет проверки кодировки
  и CONST_SCRIPTCODE для пустой подписи. Тесты: `MultisigOrderRegressionTest`.
- Native и wrapped witness-программы должны завершать внешнюю Script-проверку true;
  нулевой и отрицательно-нулевой program не пропускается. Native P2A распознаётся
  отдельно; witness у P2A запрещён политикой. Тесты: `WitnessOuterScriptTest`.
- `StandardTransactionPolicy` проверяет push-only scriptSig, типы выходов, bare multisig,
  суммарный datacarrier budget, dust, P2SH sigops, P2WSH limits, Taproot annex и размеры
  аргументов. Общий лимит standard sigops — 16000; vsize для комиссии учитывает sigops.
- `MempoolGraphPolicy` проверяет RBF: расходы конфликтов и потомков, повышение feerate,
  суммарной комиссии и incremental fee, ограничение 100 заменяемых транзакций,
  отсутствие новых неподтверждённых входов. Отказ не меняет старый mempool.
- Проверяются ancestor/descendant count и size; добавлены ограничения наследования
  версии, размеров и глубины TRUC. Пул ограничен по virtual bytes; при переполнении
  вытесняется семейство с наименьшей descendant feerate. `expire` удаляет старые
  транзакции вместе с потомками. Сервис вызывает его при обращении к mempool.
- `MempoolManagementTest` покрывает RBF, откат, TRUC, зависимости, переполнение и expiry;
  `StandardTransactionPolicyTest` — границы стандартности; `NodeConfigurationTest` —
  создание сервиса и освобождение базы.

```powershell
.\mvnw.cmd -o test -q
.\mvnw.cmd -o -pl chain -am test -q
```

Первый запуск требует уже загруженных зависимостей Maven при использовании `-o`.

## Границы проверки и дальнейшая работа

- Полная историческая цепь mainnet не воспроизводилась. Дифференциальный прогон
  script/transaction/block-векторов Bitcoin Core и сравнение результатов ещё нужны.
- Локальные тесты Signet используют собственный подписанный challenge; импорт реальных
  блоков публичного Signet пока не проверен.
- Реализация не считается готовой к промышленной эксплуатации только на основании
  этих тестов: нужны проверки синхронизации, восстановления и длительной работы.
- Политики mempool/relay, сборка шаблона блока и запуск цикла майнинга остаются отдельными
  этапами. Текущие исправления не подтверждают готовность этих подсистем.
- Политика mempool ещё не является точной копией всех режимов Core v30.0: нет package
  admission/relay (в том числе полного ephemeral-dust package flow), package RBF,
  TRUC sibling eviction и CPFP carve-out. Порог dust и datacarrier budget пока фиксированы.
- Ограничение размера пула считает virtual bytes, а не динамическую память аллокатора
  Core; rolling minimum fee и его decay пока отсутствуют. Алгоритмы обхода зависимостей
  требуют нагрузочного тестирования и оптимизации для большого mempool.

## Первичные источники

- [BIP340 и официальные векторы](https://github.com/bitcoin/bips/tree/master/bip-0340)
- [BIP341](https://github.com/bitcoin/bips/blob/master/bip-0341.mediawiki)
- [BIP342](https://github.com/bitcoin/bips/blob/master/bip-0342.mediawiki)
- [Bitcoin Core v30.0: Signet](https://github.com/bitcoin/bitcoin/blob/v30.0/src/signet.cpp)
- [Bitcoin Core v30.0: validation](https://github.com/bitcoin/bitcoin/blob/v30.0/src/validation.cpp)
- [Bitcoin Core v30.0: Script](https://github.com/bitcoin/bitcoin/blob/v30.0/src/script/interpreter.cpp)
- [Bitcoin Core v30.0: policy](https://github.com/bitcoin/bitcoin/blob/v30.0/src/policy/policy.cpp)

Происхождение сохранённых векторов указано рядом с ними в `crypto/src/test/resources/bip340`
и `script/src/test/resources/bip341`.
