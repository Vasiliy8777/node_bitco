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
| BIP9 по предкам ветки; ретроспективные флаги Script Core v30 отдельно | `TaprootDeployment`, `ConsensusScriptFlags` | `TaprootDeploymentTest`, `ConsensusScriptFlagsTest` |
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
а их дети проверяются по новым UTXO. Для возврата транзакций отключённых блоков используется атомарный `reconcile` в порядке зависимостей. Эти действия теперь автоматически выполняет `NodeValidationService`:
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
- Локальный `admitPackage` атомарно принимает child-with-independent-parents; это не
  RPC `submitpackage` с частичными результатами. Сетевой package relay не реализован.
- Ограничение размера пула считает virtual bytes, а не динамическую память аллокатора
  Core. Алгоритмы обхода зависимостей требуют нагрузочного тестирования.

## Пакеты и дополнительные политики

- `PackagePolicy` проверяет порядок, конфликты и ограничения пакета (25 транзакций,
  404000 weight), расходование ephemeral dust, пакетную комиссию и package RBF
  с проверкой улучшения fee diagram. Комиссии уже известных родителей не используются
  повторно; высокая комиссия отдельно проходящего родителя не субсидирует ребёнка.
- `MempoolGraphPolicy` поддерживает TRUC sibling eviction и CPFP carve-out.
  Проверка новых неподтверждённых входов RBF сравнивает идентификаторы родителей.
- `RollingMinimumFee` повышает порог после вытеснения и снижает его после нового блока
  с учётом времени и заполнения пула. `reconcile` ограничивает размер после возврата
  транзакций из отключённых блоков и атомарно обновляет пул.
- `MempoolPolicy(FeeRate, int, long)` позволяет задать datacarrier budget и dust relay
  fee в sat/kvB. Это локальная политика, а не дополнительные ограничения блоков.
- `NodeValidationService.admitPackage` использует общую блокировку цепи и актуальный
  UTXO-контекст. Тесты: `PackageAdmissionTest`, `ExtendedMempoolPolicyTest`.
- `BitcoinCoreSighashVectorsTest` проверяет все 500 legacy sighash-векторов Core v30.0.
  Это покрытие вычисления хеша подписи, а не дифференциальный прогон всего консенсуса.
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

## Явный флаг SIGPUSHONLY

Добавлен `ScriptVerifyFlags.SIGPUSHONLY` и его проверка в `LegacyScriptVerifier`
и `InputScriptValidator`. Флаг не добавлен в контекстные правила блоков:
вне P2SH запрет исполняемых операций в scriptSig применяется только при явном
включении этого режима. Проверка стандартности mempool уже требует push-only.
Числовые маски проекта внутренние и не совместимы с числовыми масками Core;
при импорте внешних векторов флаги следует сопоставлять по именам.
`SigPushOnlyTest` проверяет включение/выключение, неминимальный push, числовые
push-операции, повреждённый PUSHDATA и OP_RESERVED.

## Исправление исторического Taproot-исключения

`ConsensusScriptFlags` больше не возвращает преждевременно P2SH | WITNESS для
исторического mainnet-исключения: DERSIG, CLTV, CSV и NULLDUMMY сохраняются согласно
высоте. Исключение подавляет Taproot только в mainnet. Проверены обе перегрузки,
сетевая область исключения и отказ по CLTV через `InputScriptValidator`.
Базовая маска проверки Script приведена к Core v30.0: P2SH | WITNESS | TAPROOT. Исключения BIP16 заменяют её на NONE; исключение Taproot — на P2SH | WITNESS. Затем добавляются DERSIG, CLTV, CSV и NULLDUMMY по высоте. BlockConnectChangesBuilder использует эту маску независимо от BIP9; проверка witness commitment сохраняет собственную высоту активации. Тесты deployment проверяют механизм BIP9 отдельно и не определяют эту ретроспективную маску.

## Импорт имён флагов

`ScriptVerifyFlags.parseNames` разбирает символические списки флагов для внешних
тестовых векторов без переноса числовых масок Core. Неизвестные имена, числовые маски
и повреждённые списки отклоняются. `ScriptVerifyFlagNamesTest` проверяет все объявленные
флаги, отсутствие пересечений битов и ошибочные строки. Это подготовка адаптера;
полный набор `script_tests.json` пока не подключён.

## Реорганизация и TRUC: уточнение аудита

Core v30 `MaybeUpdateMempoolForReorg` намеренно допускает нарушения наследования
версии и топологии TRUC после реорганизации. Поэтому отсутствие этих проверок при
восстановлении само по себе не является расхождением. Исправлен глобальный обход
TRUC при обычном приёме: посторонняя цепочка, возвращённая реорганизацией, больше
не блокирует независимые транзакции. Проверки выполняются для принимаемой транзакции
и её предков. `ReorgTrucPolicyTest` проверяет возвращение родителя иной версии,
независимый приём, отказ при расширении недопустимой цепочки и удаление ребёнка
при недоступном родителе.

Остаются открытыми: точное поведение замены конфликтов при восстановлении,
интеграционное покрытие исторических масок через BlockProcessor, полный импорт
внешних векторов, майнинг, P2P/RPC и испытания аварийного восстановления.

## Конфликты при восстановлении mempool

`reconcile` теперь начинает с существующего пула, исключив подтверждённые транзакции.
Возвращаемые транзакции проходят RBF-проверки против конфликтов и их потомков;
обход минимальной комиссии и TRUC при восстановлении не отключает RBF.
После возврата родителей восстанавливается топологический порядок и выполняется
повторная валидация по новой цепи. Ошибки хранения до завершения операции не меняют
исходный пул. Регрессионный тест проверяет недостаточную комиссию замены с потомком
и успешное замещение при достаточной комиссии. Это закрывает прежнюю безусловную
победу отключённых транзакций из-за порядка вставки; полный дифференциальный прогон
реорганизаций с Core остаётся необходимым.

## Первый прогон внешних Script-векторов

`BitcoinCoreScriptVectorsTest` исполняет первые 100 legacy-примеров `script_tests.json`
Core v30.0 в исходном порядке. Адаптер поддерживает текстовые opcodes, числовые и
строковые push и вставки сырых байтов. Тестовые credit/spend-транзакции строятся по
формату Core. Проверяется принятие/отказ, а не точный код ScriptError. Все 100 прошли.
Исходный JSON и происхождение проекции сохранены в `script/src/test/resources/bitcoin-core-v30`.
Остальные строки, witness-примеры и tx_valid/tx_invalid ещё не подключены;
этот прогон не подтверждает полную совместимость Script.

## Расширение Script-корпуса до 1101 примера

`BitcoinCoreScriptVectorsTest` перенесён в consensus и проверяет все 1101 строки
Core v30 без явного witness-массива через `InputScriptValidator`. В том числе
проверяется witness-программа с отсутствующим witness; прежний legacy-only вызов
не подходил для этого случая. Все 1101 строки прошли после исправления пустого
публичного ключа в `PublicKey.fromBytes`: неверная кодировка теперь даёт ожидаемый
отказ проверки подписи вместо ArrayIndexOutOfBoundsException из Bouncy Castle.
Явные witness-массивы и точные коды ошибок остаются за пределами этого прогона.
Проекция и её происхождение находятся в `consensus/src/test/resources/bitcoin-core-v30`.

## Полный набор script_tests.json

Адаптер расширен до всех 1212 строк Core v30, включая явный witness, суммы входов
и четыре примера с автоматически создаваемым Taproot-выходом. Проекция `script-all.txt`
сохраняет пустые witness-элементы, порядок и ожидаемые результаты. Специальные маркеры
обрабатываются по `src/test/script_tests.cpp` Core. Проверяется принятие/отказ;
точные ScriptError, tx_valid/tx_invalid и независимый прогон блоков ещё не подключены.

## Транзакционные векторы Core

`BitcoinCoreTransactionVectorsTest` подключает все 120 tx_valid и 93 tx_invalid
примера Core v30. Учтены исключаемые флаги valid-набора, включаемые флаги invalid,
BADTX, prevout scripts и суммы входов. Проверяется базовая структура и Script;
это не проверка maturity/финальности по реальной цепи. Случайные комбинации флагов
из C++ test harness пока не воспроизводятся.
Векторы выявили пропуск CONST_SCRIPTCODE: legacy OP_CODESEPARATOR теперь запрещён
при этом флаге, включая неисполняемые ветки. Witness v0 и Tapscript этим запретом
не затрагиваются. Все 213 транзакционных и 1212 Script-примеров проходят.

`ConstScriptCodeSeparatorTest` отдельно закрепляет границы CONST_SCRIPTCODE:
legacy CODESEPARATOR запрещён в исполняемой и неисполняемой ветке при включённом
флаге, разрешён без него; witness v0 не получает этот запрет; байт 0xab внутри
push-данных не считается opcode. Это дополняет реальные tx_invalid-векторы.

## Реальные блоки mainnet

`HistoricalMainnetBlocksTest` импортирует сохранённые wire-блоки mainnet высот 1–3
через NodeValidationService/BlockProcessor и RocksDB. Проверяются закреплённые хеши,
высоты, coinbase UTXO (сумма, скрипт, maturity metadata) и состояние после чистого
закрытия и повторного открытия базы. Источник и границы теста описаны рядом с данными.
Это не сравнение с запущенным Core, не тест аварийного завершения и не проверка
исторических исключений/активаций. bitcoind и bitcoin-cli при проверке не найдены в PATH.

## Независимый Bitcoin Core regtest

Обнаружен и запущен Bitcoin Core v31.1.0 из обновлённого PATH. В отдельной временной
базе без сетевых соединений добыты три блока через generatetodescriptor raw(51).
Сохранены wire-блоки, закреплённые хеши, getblockchaininfo и gettxout для coinbase.
`BitcoinCoreRegtestBlocksTest` импортирует эти блоки и сверяет вершину, суммы 50 BTC,
скрипт 51, высоты и признак coinbase, затем повторно открывает базу.
Экземпляр Core остановлен. Это ограниченное независимое сравнение; полный UTXO set,
траты, реорганизации и принятие Core блоков нашего будущего майнера ещё не проверены.
Версия Core этого эксперимента — 31.1.0, версия ранее импортированных векторов — 30.0.

## Трата созревшей coinbase из Core

`BitcoinCoreSpendTest` импортирует 102 блока независимого Core v31.1.0. Последний
содержит трату coinbase блока 1 с комиссией 10000 sat. Проверяются существование
исходного UTXO перед тратой, его удаление, новый UTXO 4999990000 sat, высота,
признак non-coinbase, coinbase с комиссией и сохранение после чистого перезапуска.
Для входа OP_TRUE в изолированном Core включён acceptnonstdtxn; настройки консенсуса
не обходились. Хеши и результат gettxout сохранены рядом с блоками в core-spend.
Это ещё не сравнение полного UTXO set и не тест реорганизации/аварии.

## Независимая ветка реорганизации

Core v31.1.0 создал альтернативные блоки 102–103 без mempool-транзакций после
отключения блока с тратой. `BitcoinCoreReorgTest` подаёт обе ветки обычному
BlockProcessor: равная по работе ветка остаётся боковой, более длинная активируется.
Проверяются восстановление потраченной coinbase, удаление выхода отключённой траты
и coinbase отключённого блока, сохранение вершины и монет после чистого перезапуска.
Ответы Core gettxout получены с include_mempool=false. Полный digest UTXO set и
аварийное завершение ещё не проверены.

## Полное сравнение UTXO тестовой ветки

После реорганизации на высоту 103 сохранены все 103 UTXO Core: полнота результата
scantxoutset подтверждена равенством с gettxoutsetinfo.txouts. Тест сравнивает каждый
outpoint, сумму, скрипт, высоту, coinbase-признак и общее количество записей нашей
базы до и после повторного открытия. Для подсчёта добавлен диагностический count
с потоковым обходом namespace RocksDB; вызывающий код обязан исключать запись.
Это полное сравнение данной небольшой regtest-цепи, не всей mainnet и не аварийный тест.

Полный подсчёт выявил и помог исправить лишние UTXO: выходы с начальным OP_RETURN
(включая witness commitment) и скрипты длиннее 10000 байт больше не добавляются
в набор монет. Отключение блока учитывает тот же фильтр. Ранее созданные базы могут
содержать такие записи: автоматическая миграция существующей базы не реализована;
для сопоставимого UTXO set требуется перестроение цепного состояния.
