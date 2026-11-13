# Mempool и пакетный приём: реализация и приёмка Core 32.1

Пользователь потребовал закрывать направления целиком. Этот файл фиксирует
единый объём, не объявляя отдельные изменения завершённым направлением.
Эталон зафиксирован: Bitcoin Core v32.1. Прежние отчёты об отдельных исправлениях
являются историческими свидетельствами, а не критериями полного соответствия.

Статус 13 ноября 2026: указанные восемь подпунктов mempool/приёма пакетов
реализованы, прошли независимые сравнения, полную сборку и рабочий запуск.
Текущие статусы остальных направлений — в [пользовательском списке](core32-user-checklist.md).

## Выполненная реализация и независимые проверки

- Полностью перенесены SpanningForestState, SFLDefaultCostModel, RandomMixin/
  xoroshiro128++, Linearize и PostLinearize. 9600 случаев неизменённого C++ Core
  совпадают по порядку, стоимости и optimal, включая продолжение из предыдущего
  результата бюджета 75000. `core32-sfl-audit.log`.
- Production-путь переключён на SFL; прежний exact maximum-closure solver
  перенесён в test scope как независимый дополнительный эталон для 384 графов.
- Граф хранит порядок, chunk и качество NEEDS_FIX/NEEDS_RELINEARIZE/ACCEPTABLE/
  OPTIMAL. Используются Core budgets 75000 и 375000, сохранение прежнего порядка
  и корректные точки расходования бюджета. RPC optimal больше не константа.
- Main/staging разделяют неизменяемые узлы, компоненты и снимки оптимизации;
  модификации заменяют затронутые объекты. Владение картами и очередями независимо.
  3600 полностью оптимизированных снимков исходного Core TxGraph совпадают по
  main/top отношениям, mining chunks, худшему chunk, commit/abort и fee changes.
  В этом графовом oracle внешний RNG детерминирован, allocator shim не является
  проверкой memory accounting. Отдельные SFL-векторы используют настоящий RNG.
- Изолированный прогон mempool/NodeValidationService: 10297 тестов без ошибок
  (`core32-sfl-isolated-tests.log`), до последующих изменений script diagnostics.
- Типизированы все ScriptErrorString Core. Существующие 1237 скриптовых векторов
  теперь проверяют точный ScriptError, а не только boolean; все проходят, включая
  130 witness-векторов и пять Taproot-векторов. `core32-script-diagnostic-vectors.log`.
- Подробный отказ передаётся через параллельную input queue. Обычные boolean API
  сохранены; mempool admission/submission получают canonical reason и debug input.
- Прежние два native RPC/пакетных сценария прошли после подключения SFL и новой
  диагностики: `core32-mempool-complete-native-baseline.log`.

## Этапы полной приёмки

Дополнение 2026-11-13: строгая native-матрица расширена до 85 script/policy
сценариев и 698 полных RPC-сравнений; все совпадают. В неё включены пары версий
2/3, пределы BIP54/weighted sigops, структурные отказы, подтверждение всего пула,
многовходовые RBF и добавление/очистка fee deltas. Исправлена формула adjusted
weight: Core умножает sigop cost на 20, а не на 80. Исправлены дополнительные
приоритеты и canonical reasons структурных отказов.

Добавлен настоящий allocator oracle графа: `generate-memory-vectors.ps1`
использует неизменённый Core `memusage.h`. Java совпадает во всех 3600 снимках
main/staging. Добавлена оценка транзакций с актуальным prevector<36>, ёмкости
randomized index и mapDeltas. `dynamicMemoryUsage` и RPC `usage` совпадают с
native Core во всей расширенной матрице, включая подтверждение и RBF.
Последний изолированный mempool/NodeValidationService прогон: 10323/10323.
Последующие изменения перевели вытеснение и rolling-fee decay на memory budget.
Native pressure-набор использует 1100 пакетов, лимит 1 МБ и большой witness:
проверяет состав пула и память, успешную пробу без вытеснения, а также удаление
старой слабой транзакции при последующем отказе новой. Исправлено ошибочное
включение capacity eviction в replaced-transactions. Отдельно реализованы
PackageTRUCChecks и точные причины слишком большого ребёнка/ancestor/descendant.

512 независимых сценариев Core TxGraph::Trim совпали по удаляемым транзакциям.
В реальном native reorg две воскресшие родительские транзакции соединяют 70
потомков: Core и Java сохраняют одинаковые 64 из 72 транзакций и одинаковую
оценку памяти. RBF теперь использует сохранённые main/staging chunks; 383
независимых пары полностью оптимизированных диаграмм совпадают с Core.

Дополнительно исправлены CheckTransaction/finality/BIP68 priority, причины
ошибок значений/повторяющихся/null inputs/coinbase/maturity и общая фаза
проверки пакетов. Отрицательное значение выхода десериализуется как в Core,
после чего отклоняется consensus-проверкой с bad-txns-vout-negative.
Существующие семейства consensus-исключений и внутренние сообщения сохранены;
RPC получает отдельные reason/debug. Protocol/script/consensus: 2684 успешных
теста, один прерван условием окружения, без ошибок. Последний расширенный
изолированный mempool/NodeValidationService прогон: 11218/11218.

Полная Maven-сборка и рабочий перезапуск выполняются сейчас; результаты ниже
должны быть обновлены после фактического завершения.

1. [x] Расширена строгая native матрица script/policy/partial submit, включая Taproot,
   ошибку в каждой позиции пакета, client cap и проверку состояния обоих пулов.
2. [x] Сопоставлена общая фаза TRUC/cluster/ephemeral и приоритеты отказов;
   исправлены найденные расхождения в production, проверены метаданные новых/
   известных транзакций и перекрывающиеся RBF/eviction-пакеты.
3. [x] Проверены reorg/oversized cluster trim и memory-pressure eviction с Core;
   virtual-byte бюджет заменён allocator estimate Core.
4. [x] Добавлены сценарии качества/продолжения и COW snapshots,
   повторены полные script/consensus/mempool/нативные проверки.
5. [x] Полные reactors Bitcoin/RVN, упаковка и перезапуск исходной IDEA GPU-ноды;
   проверены RUNNING/IBD/высота/пиры. Таблицы приёмки обновлены.

На момент первоначальной записи production работал на последней проверенной
сборке, PID 20332. Новая реализация испытывается в отдельном output directory
`target/sfl-audit`, не изменяя загруженные production class files. Финального
Maven package и переключения ноды на новую реализацию тогда ещё не было.

## Завершающая проверка expiry и финализации

Нативный сценарий с setmocktime +400 часов подтверждает: чтение, проба,
отрицательное значение выхода и повтор известного txid не удаляют старый пул.
Приём новой допустимой независимой транзакции удаляет просроченную семью.
Core и Java оставляют одну и ту же новую транзакцию, с одинаковой памятью.
В pressure-сценарии отказ новой записи после удаления старой теперь также
обновляет mining revision. PackageTRUCChecks перенесён отдельно от SingleTRUCChecks.

Expiry выполняется при финализации после проверок; read/query/template/feefilter
API не запускают его. Бюджет POST_CHANGE_COST расходуется перед expiry/trim,
стадия пробы не выполняет финализацию. Результат графа и историческая ёмкость
индекса сохраняются при публикации. Добавлен отдельный индекс arrival time:
проверка отсутствия просроченных записей не обходит весь пул на каждом приёме.

Расширенная матрица: pressure=true — 8872 полных RPC-сравнения, strict — 826;
всего 9698, расхождений нет. Изолированный mempool/NodeValidationService:
11223 успешных теста, включая дополнительные ранние отказы с maxfeerate=0
и все пути чтения. `core32-mempool-expiry-native.log`,
`core32-finalization-expiry-regression.log`.

Первый полный набор Bitcoin/RVN проверил 15978 случаев (12 skips), после
исправления старого ожидания отсутствия RPC usage. Обе сборки упакованы.
Промежуточный запуск PID 142192 подтвердил RUNNING, blocks=headers=975638,
IBD=false, 9 пиров, 36069 записей, usage=107289224 и работающий CUDA.
Последние expiry-изменения проходят повторный полный Bitcoin reactor;
финальный PID/health и итоговое число тестов добавляются после его завершения.

## Финальная сборка и рабочая проверка

- Bitcoin: `core32-complete-mempool-final-bitcoin-build.log`, BUILD SUCCESS,
  13 ноября 2026, 14:13:25 Bangkok. 15620 тестов, без failures/errors, 12 skips.
- RVN: `core32-complete-mempool-rvn-package.log`, BUILD SUCCESS. Последующие
  изменения относятся только к Bitcoin mempool/app и не меняли RVN-модули.
- По уникальным именам тестовых классов обоих reactors: 558 классов, 15983
  случая, 15971 успешный, 12 skips, без failures/errors. Сводка:
  `core32-complete-mempool-final-test-summary.json`.
- Запущена исходная `BitcoinNodeApplication GPU`, профили mainnet,gpu,
  PID 135460. Один production Bitcoin JVM, тестовых bitcoind не осталось.
- RUNNING; blocks=headers=975639; IBD=false; 8 пиров, включая 2 block relay;
  privileged peers=0. Mempool: 38514 записей, bytes=21373346,
  usage=112966072/300000000, optimal=true.
- CUDA: около 2601 MH/s, accepted=29. Финальный RPC-снимок:
  `core32-complete-mempool-final-live.json`.

Оценка usage воспроизводит модель Core, а не измеряет retained heap JVM.
Векторы диаграмм и TxGraph сравнивают полностью оптимизированные состояния;
ограниченный бюджет/стоимость/continuation отдельно проверены 9600 точными
SFL-векторами с настоящим seeded RNG. Остальные направления общего аудита
сохраняют свои отдельные критерии в пользовательском списке.
