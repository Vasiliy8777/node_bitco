# Аудит майнинговой ноды — 29 сентября 2026

Проверен commit `b757f26`. Метод: статический обзор основных путей headers/storage,
консенсуса заголовков, сборки шаблонов, Stratum и сертификационных скриптов;
общий Maven-прогон. Это не построчная проверка всего проекта и не подтверждение
полной эквивалентности Bitcoin Core. Производственный код в рамках аудита не менялся.

## Вывод

Проект содержит существенную реализацию full node и solo mining: проверку блоков,
UTXO/undo/reorg, mempool, P2P, GBT/submitblock и Stratum V1. Готовность к эксплуатации
в mainnet пока нельзя считать доказанной. Основные следующие задачи — измеримая
проверка консенсуса и восстановления, устранение задержек выдачи работы и испытания
с реальным mining proxy/ASIC. Совпадение всех RPC и mempool-политик Core не является
необходимым условием консенсусной совместимости; Stratum V2 и пуловые выплаты —
отдельный продуктовый объём, а не обязательные свойства solo-ноды.

## Находки

### P1: сертификация публичной сети недостаточна для заявленного полного соответствия

`tools/real-network-core-differential.ps1` проверяет tip/chainwork, наличие пиров
и raw headers/blocks только на четырёх высотах: 0, H/3, 2H/3, H.
В скрипте нет сравнения UTXO и проверки исторических границ активаций.
Совпадающие байты полученных блоков не доказывают одинаковое применение транзакций.
Regtest differential с UTXO существует отдельно, но не заменяет исторический mainnet replay.

Критерий закрытия: независимая синхронизация mainnet с явно отключённым пропуском
проверок скриптов через assumevalid; согласованные контрольные высоты с tip,
chainwork и UTXO digest; переходы BIP16/34/65/66/68/112/113/141/143/147/341/342,
исторические исключения BIP30 и отрицательные ветки. Зафиксировать версию/commit
Core, конфигурацию и результаты. Нужны также детерминированные дифференциальные
проверки невалидных блоков: реальная история содержит преимущественно валидные данные.

### P1: объявление CLOSED не подтверждает аварийные испытания

`tools/crash-recovery-resource-dos-stage-gate.ps1` запускает обычные тесты,
проверяет наличие файлов и строк в исходниках, затем объявляет этап CLOSED.
Сам скрипт не организует принудительное завершение процесса в точках commit,
переполнение диска или длительную ресурсную нагрузку. Наличие теста и успешное
переоткрытие БД после штатного close не равны восстановлению после аварии.

Критерий закрытия: отдельный процесс и fault injection до/после commit блоков,
UTXO, undo, active tip и best header; повторный запуск; сверка состояния;
I/O errors и отсутствие свободного места. Отдельно измерить RSS/native memory,
очереди и задержки при перегрузке P2P/RPC/Stratum. Не объявлять crash/power-loss
гарантии только на основании unit-тестов.

### P1: риск задержки выдачи работы всем Stratum-сессиям

`StratumSession.run` обрабатывает запрос под монитором сессии; `submit` под этим
монитором вызывает backend и может выполнять валидацию/сохранение блока.
`StratumServer.refresh` единственным updater-потоком последовательно вызывает
`synchronized publish` для каждой сессии. Поэтому ожидание занятого монитора одной
сессии задерживает рассылку следующим. Это вывод из блокировок; длительность
задержки в runtime в этом аудите не измерена. Дополнительно refresh опрашивает
backend раз в секунду с fixed delay после завершения предыдущего цикла.

Критерий закрытия: короткие критические секции; тяжёлая обработка submit вне
монитора доставки; уведомление о смене tip; тест изоляции с задержанным backend;
метрики p50/p95/p99 от принятия нового tip до notify каждому майнеру.

### P2: Testnet4/BIP94 реализован лишь частично

**Обновление 30 сентября:** пробел реализации закрыт, см. [Testnet4](TESTNET4.md)
и результаты седьмого этапа ниже. Далее сохранена исходная находка аудита.

`BitcoinNetwork` и `NetworkParametersRegistry` не предоставляют Testnet4.
`NextWorkRequired` поддерживает выбор первого bits периода через enforceBip94,
но `ChainHeaderValidator` не проверяет на границе периода
`candidate.nTime >= parent.nTime - 600`. Это незавершённая возможность, а не
обнаруженное нарушение mainnet-консенсуса. Сертификационный скрипт при этом
допускает ExpectedChain=testnet4.

Критерий закрытия при включении Testnet4 в объём: параметры сети, genesis,
активации, P2P/RPC-конфигурация, полная проверка BIP94 и одинаковые ограничения
для block/header/proposal/template. Граничные тесты: parentTime-601 и -600,
высоты 2015/2016/2017; mainnet/Testnet3 не должны получить правило Testnet4.

Источник: https://github.com/bitcoin/bips/blob/master/bip-0094.mediawiki

## KnownHeaderStorage

Правильная основа: `saveBatch` помещает primary/work/height/skip indexes и best
header tip в один RocksDB WriteBatch и вызывает `database.write(batch, true)`.
Наличие заголовка отделено от полной валидации блока. Проверять PoW и контекст
внутри этого storage-класса повторно не требуется: это задача HeaderProcessor /
ChainHeaderValidator.

Контракты и пробелы:

1. `save` не записывает skip hash, в отличие от `saveBatch`. StoredBlockIndexLookup
   при отсутствии skip переходит к родителю, поэтому это прежде всего разница
   производительности, а не доказанная ошибка выбора цепи. Следует унифицировать
   путь записи либо явно ограничить применение одиночного метода.
2. `saveBatch` доверяет новизне индексов, порядку родителей и resolvedSkipHashes;
   bestHeaderTip не проверяется на присутствие в batch/БД. Это внутренний API
   доверенной записи, его нельзя подавать напрямую на RPC/P2P. Контракт общей
   блокировки и принадлежности всех store одной базе следует сделать явным.
3. Нужны негативные тесты именно batch: отсутствующий skip для последнего элемента,
   отсутствующий предок, исключение до commit — никакие ранее добавленные элементы
   и tip не должны сохраниться. Проверять все вторичные индексы после reopen.
4. В HeaderBatchProcessor ancestry внутри overlay идёт линейно по родителям,
   хотя resolvedSkipHashes уже накапливаются. Для пакета N заголовков возможна
   квадратичная стоимость локальных обходов; сетевой лимит ограничивает N.
   Это гипотеза оптимизации для бенчмарка, а не измеренный bottleneck mainnet.
5. onValidatedHeader вызывается до durable commit. Это событие проверки,
   а не подтверждение сохранения: обработчики не должны трактовать его как commit.

Не следует добавлять повторные RocksDB lookup для каждой записи без измерения:
fast path специально исключает эти чтения. Защиту инвариантов лучше обеспечить
единственным владельцем записи и тестами отказов.

## Последовательность доведения

1. Закрепить целевой объём: mainnet full node + solo ASIC через GBT/Stratum V1;
   требования к другим сетям учитывать отдельно.
2. Проверить storage-инварианты и изоляцию Stratum; добавить воспроизводящие тесты
   перед изменениями. Измерить время template construction под chain lock.
3. Пройти изолированный Core regtest differential: валидные/невалидные блоки,
   witness, reorg, invalidation/reconsideration, GBT и Stratum.
4. Выполнить mainnet replay и UTXO-сверку; сохранить воспроизводимые артефакты.
5. Провести аварийные и длительные нагрузочные испытания, затем ASIC/proxy soak
   с метриками stale/rejected shares, job latency, block relay latency и recovery.

Документацию нужно свести к одной актуальной матрице: например, mining-node.md
ещё заявляет отсутствие proposal mode и compact-block relay, хотя соответствующие
реализации/новые документы уже присутствуют. Нельзя переносить старые списки
недостатков в актуальный backlog без проверки кода.

## Проверки этого аудита

`./mvnw.cmd -o test -q`: exit 0; по Surefire XML 3754 теста,
0 failures, 0 errors, 4 skipped. Лог: `../audit-tests.log`.
Обычный прогон не включает условный Core mining roundtrip без bitcoin.core.binary.
Отдельно выполнен BitcoinCoreMiningRoundTripTest с установленным Core 31.1.0:
1 тест, 0 failures/errors/skipped, 6.581 с, Maven exit 0.
Лог: `../audit-core-roundtrip.log`. Проверен изолированный regtest roundtrip
с транзакцией, приёмом блока Core, reconnect и Stratum.
Полный replay mainnet, live differential двух синхронизированных нод,
power-loss и реальные ASIC в этом аудите не выполнялись.

## Первый этап реализации после аудита

Реализованы согласованная запись skip-index одиночным `KnownHeaderStorage.save`
и изоляция доставки заданий Stratum по сессиям. Одиночная запись теперь требует
сохранённых предков и отклоняет отсутствующего предка до commit. Общий resolver
проверяет точное уменьшение высоты, чтобы повреждённый индекс не вызвал неверный
переход или бесконечный обход. Быстрый saveBatch с заранее проверенными skip-хешами
сохраняет прежний путь без повторных чтений RocksDB.

Тесты проверяют одиночную и пакетную запись после reopen, отказ в конце batch,
отсутствующего предка и неверную высоту. При отказе проверяются primary, skip,
сырые work/height namespaces и сохранённый tip. Это проверки атомарности до commit,
они не заменяют отдельные power-loss испытания.

Stratum updater теперь ставит независимые задачи доставки; на сессию допускается
одна ожидающая/исполняющаяся задача, повторные запросы обновления объединяются.
Задача читает актуальное задание после получения монитора сессии. Обработка submit
сохраняет последовательность внутри сессии и порядок ответа перед disconnect.
Регрессионный тест задерживает submit одного майнера и проверяет две последовательные
смены задания у второго — результат не зависит от порядка обхода сессий.

До исправлений тест skip-index падал с NoSuchElementException, тест изоляции —
с SocketTimeoutException. После исправлений целевой прогон KnownHeaderStorageTest
и StratumIntegrationTest прошёл. Дополнительные storage-сценарии включены в итоговый
общий прогон, результат которого фиксируется ниже.

Итоговый прогон: `./mvnw.cmd -o test
-Dbitcoin.core.binary=C:/Program Files/Bitcoin/daemon/bitcoind.exe` (аргумент пути
передан одной строкой). Maven exit 0; 3761 тест, 0 failures/errors, 3 skipped.
KnownHeaderStorageTest: 9 успешных; StratumIntegrationTest: 6 успешных;
BitcoinCoreMiningRoundTripTest: выполнен, 6.086 с, не пропущен.
Лог: `../storage-stratum-full.log`. `git diff --check` прошёл.

Остаются отдельными задачами: событийное обновление вместо секундного polling,
измерение задержек backend/chain lock и шаблонов, аварийные испытания, расширенный
Core differential и mainnet replay. Этот этап не закрывает весь план готовности.

## Второй этап: отдельный процесс и восстановление

Добавлен `NodeProcessCrashTest`: три изолированных JVM запускают Spring-конфигурацию
BitcoinNodeApplication на временной regtest-БД. Родитель принудительно завершает
процесс до подачи следующего блока, после подтверждённого подключения и в гонке
с подключением. После сбоя выполняются startup consistency check, сверка
tip/chainwork/raw blocks/UTXO digest/undo с независимым детерминированным прогоном,
invalidate/reconsider и подключение следующего блока.

Проверка ограничена coinbase-only блоками; гонка допускает целиком старое или новое
состояние и не доказывает остановку внутри native write. Power-loss, disk-full,
сложные spending/reorg/snapshot fault injection остаются открытыми.

`BitcoinNodeApplicationTests` переведён на временную regtest-БД с отключёнными
auto-start, P2P listener, RPC и Stratum. До этой правки contextLoads использовал
обычный testnet-профиль с автоматической синхронизацией и постоянным datadir.
Сам main-класс менять не понадобилось: запуск уже делегирован Spring lifecycle.

Первый аварийный прогон выявил отказ немедленного открытия LOCK на Windows после
завершения дочерней JVM. Отладчик подтвердил `process.isAlive() == false` перед
открытием; с задержкой открытие успешно. Harness теперь отдельно ожидает доступности
LOCK до пяти секунд, не удаляет его и не повторяет RocksDB recovery. Целевой прогон
трёх crash-сценариев и изолированного Spring-теста прошёл.

Stage gate теперь проверяет непустой успешный Surefire-отчёт crash-тестов без skipped
и сообщает о прохождении автоматических проверок, сохраняя весь этап OPEN до
аварийных испытаний диска и длительной нагрузки.

Проверка: `tools/crash-recovery-resource-dos-stage-gate.ps1` выполнил полный
`clean test` и проверку crash-отчёта, exit 0. 3764 теста, 0 failures/errors,
4 skipped; все 3 process-crash сценария выполнены, 5.820 с. Лог:
`../crash-recovery-stage.log`. Core roundtrip этим скриптом не включается и в
данном прогоне пропущен; последний успешный запуск с Core описан в первом этапе.

## Третий этап: расходование UTXO и откат цепочки — 30 сентября

Расширен NodeProcessCrashTest: теперь 12 сценариев завершения дочерней JVM до,
после и одновременно с обычным подключением блока, подключением блока с двумя
зависимыми расходованиями, откатом трёх блоков и повторным подключением этих блоков.
В фикстуре coinbase высоты 1 расходуется на высоте 102; созданный выход расходуется
в том же блоке. Invalidate на высоте 101 откатывает блоки 101–103, reconsider
подключает их обратно.

После сбоя проверяются активная цепочка, chainwork, raw blocks, undo, UTXO digest
и failure root. Для конкретных outpoint дополнительно проверяются наличие, сумма,
высота, script и coinbase flag. Промежуточный выход не должен сохраняться ни в одном
допустимом состоянии. Суммы и высоты проверяются также по явным ожидаемым значениям.

Stage gate требует все 12 имён сценариев в Surefire-отчёте без ошибок и skipped.
Целевой прогон NodeProcessCrashTest и указанного пользователем
BlockDownloadSchedulerTest прошёл; планировщик загрузки не менялся.

Это ещё не fault injection внутри native write и не переключение на конкурирующую
ветку. Ошибки диска, snapshot finalization, power-loss и длительная нагрузка
остаются открытыми. Testnet4/BIP94 этим этапом не изменён.

Итог: `tools/crash-recovery-resource-dos-stage-gate.ps1` — exit 0 после `clean test`.
3774 теста, 0 failures/errors, 4 skipped. NodeProcessCrashTest: 12 успешных,
27.46 с; BlockDownloadSchedulerTest: 11 успешных в целевом прогоне и включён
в полный. Core roundtrip в этом прогоне не активирован. Логи:
`../crash-spending-focused.log`, `../crash-spending-stage.log`.

## Четвёртый этап: конкурирующая ветка — 30 сентября

Добавлены три process-crash сценария переключения с A103 на B104, до операции,
после подтверждения и в гонке с ней. Общая история заканчивается на высоте 101;
две ветки по-разному расходуют одну зрелую coinbase. Ветка B строится в отдельной
временной БД. Её блоки 102 и 103 сохраняются как боковые при меньшей/равной работе,
а следующий блок даёт большую chainwork и инициирует переключение.

Проверяются целостное состояние A103 или B104, отсутствие смешения UTXO веток,
конкретный surviving outpoint, сумма, высота и coinbase flag. При восстановлении
A103 выполняется повторная отправка B104. Далее проверяются invalidate/reconsider
и продолжение цепи. Stage gate требует все 15 имён crash-сценариев.

Целевой прогон NodeProcessCrashTest и BlockDownloadSchedulerTest прошёл без ошибок.
Производственный код переключения веток не менялся: нарушения в новых сценариях
не воспроизвелись. Детерминированная остановка внутри native commit, disk-full,
прочие ошибки ввода-вывода и power-loss остаются открытыми; гонка не заменяет их.

Итоговый stage gate с `clean test`: exit 0, 3777 тестов, 0 failures/errors,
4 skipped. Все 15 process-crash сценариев выполнены (31.77 с), проверка их имён
в gate прошла. Core roundtrip не активирован в этом прогоне. Логи:
`../crash-fork-focused.log`, `../crash-fork-stage.log`.

## Пятый этап: отказ записи и атомарность availability — 30 сентября

Добавлен NodeStorageFailureTest: десять сценариев IOError и IOError/NoSpace при
подключении блока с расходованиями, disconnect, reconnect, переключении ветки и
записи пакета заголовков. Реальная БД используется через тестовый делегат, который
отклоняет native write до его выполнения. Проверяются все пространства ключей,
счётчики изменений, состояние цепочки и UTXO, публикация best header; затем повтор
операции без отказа, переоткрытие БД и дальнейшие переходы цепи.

Найден и исправлен побочный эффект: подготовка batch через markUndo вызывала status,
который сохранял lazy-migration availability отдельным database.put. Отладчик
подтвердил путь commit → save undo → markUndo → status → put и отсутствие прежнего
значения метаданных. Теперь legacy-флаги вычисляются без отдельной записи, а
availability сохраняется атомарно с batch. Добавлен storage-тест отмены batch,
переоткрытия и повторной записи с сохранением legacy-флагов.

Целевые тесты отказов, availability и BlockDownloadWindowStallDetectorTest прошли.
Gate требует все десять сценариев отказа без skipped и принимает параметр
`-CoreBinary` для roundtrip с Bitcoin Core. Это моделирование отказа перед записью,
а не настоящий disk-full или частичный WAL write: неопределённый результат commit,
power-loss, snapshot finalization и длительная нагрузка остаются открытыми.
Testnet4/BIP94 этим этапом не изменён.

Итог пятого этапа: `crash-recovery-resource-dos-stage-gate.ps1 -CoreBinary
'C:/Program Files/Bitcoin/daemon/bitcoind.exe'` завершился с exit 0 после `clean test`:
3788 тестов, 0 failures/errors, 3 skipped. Выполнены все 15 process-crash,
10 storage-failure, 3 availability и 4 stall-detector теста. Core mining roundtrip
выполнен без пропуска. Логи: `../storage-failure-focused.log`,
`../storage-failure-stage.log`.

## Шестой этап: отказ инициализации цепочки — 30 сентября

Проверен ChainInitializer. Добавлены четыре случая IOError/NoSpace: отказ первого
batch с genesis и отказ записи best-header tip при миграции старой БД. Проверяется
отсутствие частичной инициализации, неизменность содержимого БД и namespace versions,
повтор операции после переоткрытия и ещё одно переоткрытие с сопоставлением всех
ключей и состояния цепочки с независимой эталонной БД. Производственный код
инициализатора не менялся: нарушения в этих сценариях не воспроизвелись.

Stage gate теперь требует 14 сценариев отказов записи. Целевой прогон
NodeStorageFailureTest (14) и ChainInitializerTest (11): 25 тестов, без ошибок
и пропусков, BUILD SUCCESS. Лог: `../initialization-failure-focused.log`.
Полный reactor и Core roundtrip в этом этапе повторно не запускались; результаты
предыдущего полного прогона приведены выше. Реальные disk/WAL faults, power-loss
и snapshot finalization остаются открытыми.

## Седьмой этап: Testnet4/BIP94 и продолжение recovery — 30 сентября

Закрыт пробел реализации Testnet4: отдельная сеть и профиль, genesis с точным
сравнением с BIP94, magic/порты/DNS, активации с высоты 1, Taproot always-active,
bootstrap anchors по Core v30.0, RPC chain=testnet4. Testnet3 сохраняет прежнее
значение `testnet`. Переоткрытие Testnet4 и отказ использования БД как Testnet3
проверены в ChainInitializerTest. Детали запуска: [TESTNET4.md](TESTNET4.md).

Единая нижняя граница времени применяется в header/block/proposal validation,
шаблонах, GBT mintime и Stratum. На границе периода учитываются MTP+1 и
parent.time−600; Stratum запрещает time rolling при изменяемой от времени
сложности, включая Testnet4. Проверены высоты 2015/2016/2017, −601/−600, MTP,
retarget от первого bits периода, строгое 20-минутное исключение и возврат к
предыдущей сложности. Проверены неизменные правила Mainnet/Testnet3.
Изолированный Core подтвердил полные байты genesis Testnet4; regtest mining
roundtrip также прошёл. Публичная синхронизация Testnet4 этим не подтверждается.

Далее по recovery-плану добавлены IOError/NoSpace во всех четырёх точках записи
финализации валидированного AssumeUTXO: clear/marker, первая порция 10 000 записей,
остаток и cleanup. Предыдущие успешные commit реальны. После отказа и переоткрытия
проверяются все 10 001 записи, отсутствие старых UTXO, сохранность tip, удаление
маркеров и идемпотентность. Это тест протокола копирования с непрозрачными
key/value, не проверка декодирования coins. Целевые проверки прошли; partial WAL,
реальные ошибки файловой системы, process-crash внутри финализации, power-loss
и длительная нагрузка остаются открытыми.

Итог седьмого этапа: полный `clean test` через recovery stage gate с
`-CoreBinary 'C:/Program Files/Bitcoin/daemon/bitcoind.exe'` — exit 0,
3808 тестов, 0 failures/errors, 3 skipped. Выполнены 8 Testnet4ConsensusTest,
2 Testnet4MiningTest, 2 Core integration, 16 storage-failure, 15 process-crash
и 4 stall-detector теста. Логи: `../testnet4-focused.log`,
`../testnet4-recovery-focused.log`, `../testnet4-stage.log`.

## Восьмой этап: process-crash при финализации AssumeUTXO — 30 сентября

Добавлен SnapshotProcessCrashTest: пять остановок дочерней JVM — до финализации,
после clear/marker, после первой порции 10 000 UTXO, после остатка и после cleanup.
Тестовый делегат пропускает настоящий синхронный native write, затем удерживает
процесс на барьере с открытой БД до destroyForcibly. Production hooks не добавлены.
Запуск проходит через конструктор NodeValidationService, где финализация выполняется
до ChainInitializer. Это не остановка внутри native write и не power-loss.

Фикстура строит два валидных regtest-блока с 10 002 UTXO; активная цепочка на блок
дальше snapshot base. Маркеры активации/завершённой фоновой валидации устанавливает
harness: импорт snapshot и фоновая проверка истории не входят в этот тест.
До восстановления проверяется ожидаемая стадия копирования и маркеры. После запуска
сравниваются tip, chainwork, UTXO hash/count/value с независимо построенным эталоном,
проверяется chainstate consistency, invalidate/reconsider, новый блок и ещё одно
переоткрытие БД. Производственный код инициализации менять не потребовалось.

Целевой прогон: 5 SnapshotProcessCrashTest и 12 ChainInitializerTest без ошибок
и пропусков. Лог: `../snapshot-crash-focused.log`. Gate требует все пять имён
новых сценариев без skipped. Реальные disk/WAL faults, power-loss и длительная
нагрузка остаются открытыми.

Итог восьмого этапа: полный recovery stage gate с `clean test` и
`-CoreBinary 'C:/Program Files/Bitcoin/daemon/bitcoind.exe'` завершился с exit 0.
3813 тестов, 0 failures/errors, 3 skipped. Все 5 новых snapshot process-crash
сценариев выполнены (91.49 с), оба Core integration теста прошли без пропусков.
Лог: `../snapshot-crash-stage.log`.

## Девятый этап: process-crash при откате INVALID snapshot — 30 сентября

Добавлены два сценария SnapshotProcessCrashTest: завершение JVM до rollback и
после его синхронного native commit. Фикстура имеет исторический tip/UTXO высоты 1
и snapshot tip высоты 2; маркер INVALID устанавливает harness. Блок высоты 2 сам
по себе валиден и явно инвалидирован фикстурой. Проверяется восстановление после
решения об откате, а не обнаружение невалидности snapshot.

После перезапуска проверяются выбор исторического tip и UTXO, удаление маркеров,
сохранность failure flag и диагностических staging-байтов. Заведомо недекодируемые
staging-данные не должны читаться как активные coins. Состояние сопоставляется с
независимой эталонной цепочкой; затем выполняются явный reconsider, новый блок
и ещё одно переоткрытие. Производственный код менять не потребовалось.

Целевой прогон двух новых сценариев и ChainInitializerTest прошёл без ошибок.
Gate теперь требует все семь snapshot process-crash сценариев без пропусков.
Лог: ../snapshot-rollback-focused.log. Реальные disk/WAL faults, power-loss,
остановка внутри native write и длительная нагрузка остаются открытыми.

Итог девятого этапа: полный recovery stage gate с clean test и параметром
-CoreBinary 'C:/Program Files/Bitcoin/daemon/bitcoind.exe' — exit 0.
3816 тестов, 0 failures/errors, 3 skipped. Все 7 snapshot process-crash сценариев
выполнены (74.61 с), оба Core integration теста прошли без пропусков.
Лог: ../snapshot-rollback-stage.log.

## Десятый этап: recovery через Spring-конфигурацию — 30 сентября

NodeConfigurationTest дополнен тремя сценариями старта контекста с настоящими
NetworkConfiguration и NodeConfiguration: финализация VALIDATED snapshot,
продолжение копирования с маркером COPYING и rollback INVALID snapshot. Фикстура
использует блок, принятый обычным валидатором; маркеры recovery устанавливает тест.
В варианте INVALID блок явно инвалидируется до установки snapshot-маркера.
Каждый сценарий дважды открывает и закрывает Spring-контекст на одной БД.

Проверяются выбранный tip, количество и сумма активных UTXO, удаление маркеров,
очистка staging при продвижении и сохранность диагностических staging-байтов
при откате. Подтверждено создание NodeSyncInfrastructure; lifecycle остаётся NEW,
пиры отсутствуют. Это проверка Spring wiring восстановления, без запуска сети,
импорта snapshot и фоновой проверки истории. Production-код не изменён.

Целевой Maven-прогон: NodeConfigurationTest — 7, ChainInitializerTest — 12;
все 19 тестов без ошибок и пропусков, BUILD SUCCESS. Лог: ../spring-recovery-focused.log.
Полный reactor и Core integration повторно не запускались: изменены только тесты.
Реальные disk/WAL faults, power-loss и длительные нагрузочные испытания остаются открытыми.

## Одиннадцатый этап: batch, кеш и изоляция UTXO — 30 сентября

В RocksDbUtxoStoreTest добавлены два сценария. Отмена batch со spend/create
не публикует изменения; повторный commit и applyCommittedChanges обновляют
кеш, даже если чтение до commit повторно загрузило старую монету. Проверка
выполняется при ёмкости кеша 0 и 2, включая outpoint с индексом 0xffffffff,
статистику и переоткрытие БД.

Отмена очистки namespace сохраняет coins; подтверждённая очистка обычных UTXO
не затрагивает snapshot с тем же outpoint и другими данными. Проверяются hash,
переключение namespace и переоткрытие. Контракт вызова applyCommittedChanges
после commit сохранён; произвольные конкурентные записи через другие store
эти тесты не покрывают. Производственный код не изменён.

Целевой прогон RocksDbUtxoStoreTest (12) и RocksDbWriteBatchTest (8): 20 тестов,
без ошибок и пропусков, BUILD SUCCESS. Лог: ../utxo-atomicity-focused.log.
Полный reactor повторно не запускался. Это проверка корректности, не длительная
нагрузка и не эмуляция реальных disk/WAL faults или power-loss.

## Двенадцатый этап: отказ commit при включённом кеше UTXO — 30 сентября

NodeStorageFailureTest дополнен двумя случаями IOError/NoSpace при commit через
RocksDbChainTransitionStorage с кешем ёмкостью 2. Чтение после подготовки batch
повторно наполняет кеш старой монетой. После отказа проверяются все пространства
ключей и namespace versions, старые coins и tip, отсутствие новых coins и undo.
Успешный повтор должен обновить кеш внутри production commit без ручного вызова
applyCommittedChanges из теста; после переоткрытия проверяются coins, tip и undo.

Это storage-level фикстура с синтетическими индексами/coins, не новый сценарий
полной валидации блока. Производственный код не изменён. Целевой прогон:
NodeStorageFailureTest — 18, RocksDbChainTransitionStorageTest — 4,
RocksDbUtxoStoreTest — 12; все 34 теста прошли без ошибок и пропусков.
Лог: ../cached-utxo-failure-focused.log. Полный reactor повторно не запускался.
Реальные disk/WAL faults, power-loss и длительная нагрузка остаются открытыми.

## Тринадцатый этап: воспроизводимая серия изменений UTXO — 30 сентября

В RocksDbUtxoStoreTest добавлен deterministicChurnMatchesModelAcrossNamespacesAndRestarts.
При фиксированном seed выполняются 1000 batch по 16 уникальных outpoint:
855 синхронных commit и 145 отмен. Рабочее множество — 256 outpoint на namespace,
кеш — 7 записей; обычный и snapshot namespace чередуются. Независимые Map-модели
учитывают только подтверждённые изменения. До commit проверяется невидимость
подготовленных изменений, после — все затронутые coins и ограничение размера кеша.

На контрольных точках сверяются все 256 ключей каждого namespace, количество
и сумма UTXO. Между пятью сериями БД закрывается и открывается, после последней
серии выполняется ещё одно переоткрытие без кеша. Это ограниченная проверка
корректности storage под повторяющимися операциями, не измерение производительности,
не длительный soak и не реальная блоковая/ASIC-нагрузка. Production-код не изменён.

Целевой прогон: RocksDbUtxoStoreTest — 13, RocksDbWriteBatchTest — 8; все 21 тест
без ошибок и пропусков, BUILD SUCCESS. Лог: ../utxo-churn-focused.log.
Полный reactor повторно не запускался. Реальные disk/WAL faults, power-loss
и длительные нагрузочные испытания остаются открытыми.
