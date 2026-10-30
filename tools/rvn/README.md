# Запуск синхронизации RVN

Из корня репозитория в PowerShell 7:

```powershell
mvn -pl rvn-app -am package
./tools/rvn/Start-RvnSync.ps1
# С проверками CUDA и автоматическим стартом GPU после готовности цепочки:
./tools/rvn/Start-RvnSync.ps1 -Mining
```

Java 21 или новее должна находиться в PATH. Нода запускается скрытым фоновым
процессом. RocksDB и временные файлы размещаются в `C:/RVNJavaNjde`, полные
блоки, undo и логи — в `K:/RVNJavaNjde`. Параметры `-HotDirectory` и
`-ColdDirectory` позволяют задать другие каталоги. Существующие данные
сохраняются. Chainstate без актуальной отметки проверки откатывается через undo
до genesis и повторно проверяется по сохранённым блокам; после рестарта процесс
продолжается. Архивы блоков и заголовки не удаляются.

Пиры берутся из `application.yml` и `RVN_SYNC_PEERS`; отдельный запуск можно
настроить через `-Peers 'ip1,ip2'`. При отказе системного DNS используется
настроенный `ravencoin.sync.dns-over-https`. Каждая полученная ветка проходит
проверку заголовков независимо от способа обнаружения адресов.

Сведения о процессе записываются в
`rvn-app/target/rvn-sync-process.json`. Повторный запуск при живом процессе
отклоняется. Прогресс:

```powershell
Get-Content K:/RVNJavaNjde/logs/rvn-node.log -Tail 20 -Wait
```

`bestHeight` — высота проверенных заголовков, `downloadedHeight` — загруженных
тел, `checkedHeight` — состояние replay. Эти значения могут сильно различаться.
По умолчанию `RVN_SYNC_HEADERS_FIRST=true`: сначала запрашиваются и проверяются
все доступные заголовки до недавней вершины пира. Лог показывает `phase=HEADERS`,
затем `phase=BODIES`. Во время первой фазы высота тел может временно стоять;
это ожидаемо. Пир, закончивший заголовки на вершине старше двух часов, заменяется.
Недавняя вершина одного пира не доказывает глобальную актуальность сети: проверка
цепочки, mainnet checkpoint и readiness майнера продолжают действовать.
После рестарта заголовки продолжают загружаться с сохранённой вершины.
`RVN_SYNC_POW_WORKERS` (по умолчанию 4, диапазон 1–8) задаёт число независимых
CPU-проверяющих потоков. Каждый сохраняет один light-cache текущей эпохи;
потребление RAM растёт с числом потоков и номером эпохи. Значение 1 подходит
для сравнения скорости. Все результаты проходят прежнюю последовательную
проверку контекста и атомарную запись пакета. `validationMs` в строке заголовков
показывает время вычислений, контекстной проверки и фиксации этого пакета.
При включённом CUDA-воркере майнинга по умолчанию включается GPU-проверка
KawPoW-заголовков. `RVN_SYNC_GPU_POW=true/false` задаёт её явно; для режима
синхронизации без майнинга можно включить только этот параметр. Параметры Python,
устройства и резерва VRAM берутся из `ravencoin.mining.cuda.*`.
Один CUDA-процесс и один DAG используются для проверки и майнинга. Вход в GPU
разрешается после обязательных Core/CPU self-tests. Для каждого заголовка GPU
вычисляет полный mix/hash; Java сверяет каждый mix, target и контекст цепочки.
CPU пересчитывает начало, конец и два случайных элемента каждой группы эпохи;
в малых диагностических группах до 8 элементов пересчитываются все результаты.
Ошибка GPU/контрольного пересчёта останавливает проверку; автоматического
продолжения с непроверенным результатом нет.
На смене эпохи строится полный DAG в VRAM. Сообщение `entering epoch` и пауза
при подготовке ожидаемы. `RVN_CACHED_DAG_ITEMS` (65536) ограничивает CPU LRU:
16 MiB полезных данных плюс накладные расходы на каждый активный CPU-проверяющий
движок. Его неизменяемый light-cache также используется при подготовке GPU,
но CPU вычисляет DAG-элементы самостоятельно. GPU-DAG на диск не выгружается.
Тела загружаются пакетами до 128, одновременно ожидается до 16 ответов
(`RVN_SYNC_BLOCK_IN_FLIGHT`, диапазон 1–16). Очередь приёма ограничена 32 MiB;
при переполнении окно уменьшается до 8, затем до 4. Каждый ответ сопоставляется
с проверенным заголовком. Тела группируются для долговечной записи в архив и
индекс: максимум 128 тел / 8 MiB в буфере. Все затронутые сегменты архива
сбрасываются на диск до одной атомарной публикации ссылок, хешей и курсора.
Undo и chainstate по-прежнему фиксируются отдельно для каждого проверенного блока.
При `RVN_SYNC_HEADERS_FIRST=false` используется чередование заголовков и тел.
При отставании тел более чем
на `RVN_SYNC_HEADER_LEAD_BLOCKS` (4096) синхронизация сначала догружает тела.
При этом после максимум `RVN_SYNC_BODY_BATCHES_PER_HEADER` (4, диапазон 1–32)
пакетов тел обязательно запрашиваются новые заголовки. Высота `bestHeight`
показывает только уже полученные и проверенные заголовки, а не высоту всей сети.
Все заголовки поступают порциями до 2000 и проходят локальную проверку PoW.
Логи показывают время загрузки, размер окна и конкретную причину `WAITING`.
`transportMs` — время запросов и ожидания ответов, `checkStoreMs` — проверка
и сохранение полученных тел, `bytes` — их суммарный размер. Проверка chainstate
идёт отдельным шагом `RVN block replay`.
Транспорт использует буферы 64 KiB и групповые `getdata` в пределах окна.
После двух подряд медленных пакетов (не менее 32 блоков) выбирается следующий
пир. Порог — `RVN_SYNC_MINIMUM_PEER_KIB_PER_SECOND` (64 KiB/s), с допуском
не менее 5 секунд на пакет; время локальной проверки/записи исключается.
Значение 0 отключает переключение по скорости. Уже сохранённые тела остаются
доступны при смене пира.

Без `-Mining` GPU-майнинг выключен. С `-Mining` обязательные CUDA-тесты и локальный
тест задания завершаются до старта цикла майнера. `WAITING` означает ожидание
непрерывной проверки от genesis актуальной версией, совпадения текущих tip,
mainnet checkpoint и отвечающего peer. Совпадения высот без отметки проверки
недостаточно. Неподдержанный или недействительный блок останавливает replay.
Особенности профиля qualifier cache/undo описаны в
[аудите проверки](../../rvn-chain/reference/HISTORICAL_VALIDATION.md).
`RVN_MINING_ADDRESS` наследуется процессом и не записывается в файл процесса.

IBD tuning (2026-10-25): body download/replay passes accept 512 blocks; default outstanding requests increased to 32 (RVN_SYNC_BLOCK_IN_FLIGHT, 1..32). Archive staging stays bounded to 128 bodies / 8 MiB; transport budgets and adaptive window reduction remain enabled. Native coin RocksDB block cache defaults to 1024 MiB (RVN_SYNC_COIN_CACHE_MIB, 16..4096), additional to Java heap. WAL sync and undo forces remain enabled. Ordered UTXO validation remains sequential; replay logs include replayMs.

GPU search defaults to 65536 nonce per call (RVN_MINING_BATCH_SIZE, 1..65536). On RTX 4070, isolated three-sweep benchmarking measured 23.0 MH/s versus 11.8 MH/s at 4096, using the current epoch. Each candidate still receives independent CPU verification. RvnCudaKawpowBenchmark arguments: worker.py temporary-directory python-executable height. Stop the live miner before running this standalone diagnostic; it submits no blocks.

RVN mining stats are logged every 30 wall-clock seconds and immediately after an accepted/relayed local block: hashrate (MH/s), minedBlocksSession, hashesSession, intervalMs. Statistics windows span template/STALE transitions and include idle time. Session counters reset when the mining service starts; they are not lifetime totals or a count of confirmations. Only durably admitted local blocks increment minedBlocksSession; relayedBlocksSession separately tracks completed relay calls. CUDA candidates and external blocks do not increment the mined-block count.

2026-10-27: removed unconditional 30-second production mining job expiry.
Empty-block Mainnet jobs now have no wall-clock TTL; each batch still checks
parent identity, genesis validation readiness, peer freshness and chain-tip age.
Unchanged-parent modern Mainnet DGW and MTP do not require periodic retiming.
Clock rollback invalidates a template only if it exceeds the 720-second future
limit; replacement creation uses the same bound. Nonce exhaustion still creates
a new extraNonce/template. Diagnostic Job deadlines remain supported.
Tests prove continuous generation/digest/nonce at +31 and +330 seconds (64 and
65536 batches), invalidation on tip change, chain staleness, readiness loss and
future-time boundary/rollback. Debugger observed generation=1 and nonce
0,64,128,192 at time offsets 0,0,31,330. Mining tests and IDE build passed.
Thirty-second statistics reporting is unchanged; session counters reset on restart.

2026-10-27 target-hit/relay diagnostics and lower switching latency:
RVN mining stats now separate targetCandidatesSession (CPU-verified candidates
from completed non-overflow search responses), staleCandidatesSession,
minedBlocksSession (durable local admission), relayedBlocksSession (relay method
completed), admissionFailuresSession and relayFailuresSession. Counters live in
RvnSoloMining and are included in fatal error logs even when advance() throws.
Candidate discovery and local admission/relay completion also log immediately.
Overflow retries do not count discarded partial responses; counters are session
only. Successful production relay confirms peer possession, not network-wide
consensus acceptance or confirmations. Recovery of a prior session's outbox
inside backend startup is not counted as a new mined block in this session.
No sleep after a stale GPU result; only a 1 ms backoff for stale jobs before GPU
work and a 25 ms WAITING backoff. Header poll default is 2000 ms,
overridable with RVN_SYNC_POLL_MS. Mining/service and body integration tests pass;
assertions cover stale target hits, admission failure and relay retry without
counting a locally accepted block twice. IDE build/inspections pass.
