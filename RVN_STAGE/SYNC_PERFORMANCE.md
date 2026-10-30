# Синхронизация Bitcoin и RVN

## Что уже делает Bitcoin в этом проекте

- `app/.../service/NodeLifecycleService.java`: сначала
  `synchronizeHeadersWithFailover`, затем `synchronizeBlocks`.
- `app/.../sync/HeaderSyncCoordinator.java`: потоковая проверка порций
  заголовков без хранения всей цепочки в списке RAM.
- `p2p/.../sync/BlockDownloadScheduler.java`: скользящее окно до 1024,
  отдельные ограничения на каждого READY-пира и переназначение запросов.
- `app/.../sync/BlockSyncCoordinator.java`: ограниченное окно пути подключения,
  приём ответов вне порядка, проверка в порядке цепочки, prefetch UTXO.
- `app/.../NodeValidationService.java`, `processInitialSyncBatch`: логическое
  подключение каждого блока, write-back chainstate и долговечные контрольные
  точки по давлению кеша/времени; обслуживание производных индексов раз в пакет.

Это несколько независимых оптимизаций. Загрузка всех заголовков сначала не
заменяет проверку транзакций, скриптов, UTXO, активов и запись undo каждого блока.
Также Bitcoin SHA256d и RVN X16R/X16Rv2/KawPoW используют разные PoW-движки;
скорость проверки заголовков Bitcoin нельзя переносить на RVN численно.

## Что перенесено в RVN

`ravencoin.sync.headers-first=true` теперь включён по умолчанию. После
ограниченного replay уже сохранённых тел нода запрашивает заголовки до пустого
ответа у пира с недавней вершиной, сохраняя проверенные порции на SSD. Старый
конец цепочки вызывает смену пира. Затем начинается загрузка новых тел.
Перезапуск продолжает с сохранённых заголовков. При `false` остаётся чередование
с ограниченным приоритетом тел. Проверка заголовков не отключается.

Тела получают ограниченную пакетную долговечную запись. Загрузчик держит до
128 тел и 8 MiB, затем проверяет весь пакет, записывает архив, принудительно
сбрасывает все затронутые сегменты и одной синхронной транзакцией RocksDB
публикует ссылки, хеши и непрерывный курсор. Ротация сегмента сначала сбрасывает
старый сегмент. Формат архива и индекса не менялся. Отдельный предел очереди
приёма 32 MiB сохраняется; промежуточные копии также ограничены размером пакета.

Невалидный/конфликтующий пакет не публикует даже его корректную часть. При
ошибке записи архива хранилище помечается неисправным, чтобы локальный отказ
не маскировался под медленного пира. При разрыве соединения уже полученные тела
сохраняются; прерывание потока может отбросить только неопубликованный буфер.

## Проверки и измерения 2026-10-24

Тесты проверяют порядок фаз, отказ от старой вершины, ответы тел вне порядка,
целостность после ротации сегментов, конфликт в конце пакета без частичной
публикации, повторное открытие и аварийный выход отдельного JVM-процесса до
и после публикации индекса. `mvn -pl rvn-app -am package -B -q` прошёл.

На mainnet около высоты тел 128000, C: SSD / K: HDD:

| Работа на 128 тел | До пакетной записи | После |
|---|---:|---:|
| Проверка/сохранение тел | около 4 с | 39–45 мс |
| Получение + сохранение тел | около 4,2 с | 0,63–0,76 с |
| Replay в chainstate | около 4 с | около 4 с |

`transportMs` исключает время callback проверки/сохранения; это остаточное
время запроса, а не независимый замер пропускной способности линии. Сеть может
передавать данные одновременно с локальной обработкой.

Ускорение всего цикла по этим первым образцам примерно 1,5–2 раза. `added=128`
означает размер пакета, а не 128 блоков/с. Точное время полной синхронизации
не экстраполируется: поздние блоки, скрипты, активы и KawPoW дороже.

## Оставшийся предел

### GPU-проверка KawPoW (2026-10-24)

`RvnGpuHeaderVerifier` подключён к `RvnHeaderPowBatch` для полностью KawPoW
пакетов. Исторические X16R/X16Rv2 и смешанный пакет активации остаются на CPU.
GPU-режим включается вместе с CUDA-воркером майнинга или явно через
`RVN_SYNC_GPU_POW`. Он ждёт ApplicationReadyEvent после обязательных тестов.
Один процесс/контекст/DAG делится с поиском майнинговых nonce через
синхронизированный протокол воркера; копии DAG под два процесса не создаются.

CUDA `verify_headers` вычисляет каждый mix/hash. Детерминированные инструкции
для height/3 передаются как ограниченная локально построенная таблица, поэтому
NVRTC не вызывается для каждого из сотен периодов внутри пакета. Пакет до 2000
разбивается по эпохам. Java проверяет каждый embedded mix, затем прежний
контекстный валидатор проверяет target, время, nBits, высоту, связи и checkpoint.
CPU-аудит пересчитывает все элементы малых групп до 8, иначе начало/конец и
два случайных элемента. Это дополнительный контроль, а не замена полного
вычисления каждого заголовка на GPU. Ошибка локального GPU не маскируется
тихим переходом на CPU. Неверный mix/target не публикует пакет заголовков.

CPU LRU увеличен с 1024 до 65536 DAG-элементов (256 KiB -> 16 MiB полезных
данных на движок, дополнительно map overhead и light-cache). Генерируемый Java
light-cache теперь имеет внутреннюю метку эпохи и повторно используется CPU
аудитором после подготовки GPU; сокращённые/чужие кеши отклоняются. CPU
самостоятельно вычисляет DAG-элементы из неизменяемого кеша, а не читает
GPU-DAG. Устранено второе построение того же light-cache при смене эпохи.
GPU-хранилище включает ограниченное рабочее пространство ~2 MiB и полный DAG;
проверка свободной VRAM сохраняет резерв 1 GiB, без host spill. Генерация DAG
идёт ограниченными порциями по 65536 узлов вместо 8192.

Проверки: 351 RVN JUnit-тест, без ошибок/пропусков; полная сборка прошла.
Дополнительно на RTX 4070 пройдены Core-векторы, разные периоды/эпохи,
unsigned nonce/target boundaries, generic verification и все 2000 результатов
отдельного диагностического пакета сравнены с CPU. Последний standalone замер:
GPU с CPU-аудитом 120,9 ms / независимый CPU-пересчёт всех 2000 — 27618,6 ms.
Полная диагностика выполняется через main RvnCudaKawpowSelfTest; стартовый
ApplicationRunner выполняет ограниченные обязательные диагностические случаи.

На mainnet после деплоя JVM 118688: тёплая валидация 2000 заголовков
245–270 ms; с созданием нового DAG/light-cache около 11–11,6 с вместо
16 с до повторного использования light-cache. Окно из 24 пакетов:
46000 заголовков / 77,4 с = 594,4 заголовка/с с учётом эпох и ожидания сети.
Снимок nvidia-smi показывал 5106 MiB VRAM всего, включая рабочий стол/другие
программы, из 12282 MiB; найден один процесс CUDA-воркера. Это не утверждение
о постоянных 8000 заголовках/с или такой же скорости полной проверки блоков.

### Предыдущая CPU-оптимизация

На работающей ноде фаза заголовков дошла до KawPoW около высоты 1220000:
последовательный пакет из 2000 занимал примерно 34–40 секунд. Общий
`RvnKawpowLightEngine` сериализовал вычисления и смену epoch cache.

Теперь `RvnHeaderPowBatch` выполняет вычисления в ограниченном пуле:
`RVN_SYNC_POW_WORKERS=4`, диапазон 1–8, очередь не больше одного пакета
из 2000 заданий. Каждый поток владеет отдельным `RvnJavaPowEngine` и одним
light-cache эпохи. Порядок результатов соответствует входу независимо от
порядка завершения. Отмена/закрытие пула отменяет активные и ожидающие задачи.
Mix проверяется тем же `RvnPowRouter`; target, nBits, время, высота, связность,
deployment и checkpoint проходят прежние последовательные проверки перед
атомарным commit заголовков. Неверный элемент не публикует часть пакета.
Версия валидатора и отметка genesis coverage не менялись.

Первый полный прогон до изменения: 340 RVN-тестов, без ошибок/пропусков.
После изменения `mvn -pl rvn-app -am package -B -q`: 346 RVN-тестов,
без ошибок/пропусков. Дополнительные сценарии: одновременная работа
ограниченного числа потоков, порядок результатов, отмена и повторное
использование пула, закрытие с очередью, неверный PoW без частичного commit,
контекстные отказы в последовательном и параллельном режимах. Независимые
реальные light-движки проходят известный Core-вектор и совпадают с
последовательными результатами для заголовков; повреждённый mix отклоняется.

На i5-13600KF с четырьмя потоками: первый пакет около 22,2 с с прогревом,
следующие 2000 заголовков — 11,55 / 11,43 / 10,44 / 10,34 / 10,37 с.
Пакет со сменой эпохи занял 15,83 с. Это примерно трёхкратное ускорение
тёплой CPU-проверки в этих образцах. Снимок JVM подтвердил четыре RUNNABLE
потока `rvn-header-pow-*`, heap около 660 MiB при лимите 4 GiB. Расход
памяти следующих эпох будет отличаться. CUDA-векторы и локальное задание
майнера повторно прошли при запуске; GPU не заменяет CPU-проверку заголовков.

RVN сохраняет атомарные coin/asset изменения, undo и versioned coverage на
каждом блоке. Bitcoin write-back chainstate сюда ещё не перенесён. Поэтому
ещё несколько пиров или окно 1024 сейчас не устранят наблюдаемые затраты
replay. Следующий отдельный этап — ограниченный write-back UTXO/assets с
чётким разделением логического tip и долговечной контрольной точки, совместимым
undo и атомарной отметкой coverage. Простое отключение `sync` или `force`
без такого протокола восстановления недопустимо.

Производственный майнер продолжает ждать полной непрерывной проверки актуальной
цепочки; завершение фазы заголовков само по себе его не запускает.

2026-10-25 body pass/cache tuning: production JVM 125468 resumed mainnet at checked
1289133. Body/replay pass limit 512, request window default/max 32, coin RocksDB
native block cache default 1024 MiB (configurable 16..4096). Archive group size,
8 MiB staging, receive budgets, synchronous WAL and undo durability unchanged.
Replay elapsed milliseconds now logged. First two real passes: download/store
2219/1888 ms, ordered replay 22424/21590 ms, about 21 blocks/sec end-to-end.
These are nearby historical blocks, not a controlled same-input benchmark; no
claim of fourfold speedup. Main remaining cost is ordered validation/commit.
352 RVN JUnit tests passing across reports; integration fixtures updated to
exercise four 512-block passes and slow-peer rotation after two full passes.
CUDA startup vectors/local-job checks passed after restart; miner still WAITING
for validated genesis-to-tip coverage.

2026-10-27 GPU mining throughput tuning (RTX 4070, current mainnet epoch 613):
Isolated Java -> Python -> CUDA search benchmark, three alternating sweeps,
one second per size per sweep, zero target, no network submission. Core vectors,
nonce/target/overflow checks and CPU verification of diagnostic candidates passed.
Mean MH/s: 4096=11.803, 8192=15.938, 16384=19.228, 32768=21.614,
65536=23.035. Maximum measured 65536-call time 5.649 ms (short-sample result,
not a latency guarantee). Default mining batch changed 4096 -> 65536 in both
Spring fallback and application.yml; RVN_MINING_BATCH_SIZE can still override.
No additional RAM/VRAM allocations, target relaxation or candidate validation
changes. Standalone RvnCudaKawpowBenchmark reproduces tuning without block
submission; stop live miner first to avoid sharing GPU/allocating another DAG.
Mining tests cover nonce ranges and template refresh at the maximum batch size.
Live confirmation 2026-10-27 14:00 +07: JVM 28148, one CUDA worker 3936. 460521472 hashes/19603 ms = 23.49 MH/s, then 712245248/29521 ms = 24.13 MH/s. GPU snapshot 92%, 71.83 C, 191.37 W, total VRAM 7991 MiB. Real mainnet mining resumed at checked/header height 4599147. Targeted mining/service/settings tests and IntelliJ build passed; startup CUDA Core vectors and local-job CPU/native checks passed.
