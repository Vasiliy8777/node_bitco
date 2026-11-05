# Выполнение всех восьми направлений

Объём: все восемь направлений из текущего аудита Bitcoin Core 32.1. Успешная
сборка не означает, что незавершённые функции уже реализованы. Рабочая
конфигурация ноды не получает автоматически новых доверенных пиров или внешних
сетевых сервисов. Промежуточные изменения проверяются перед перезапуском одной
исходной IDEA-ноды.

| № | Направление | Состояние и критерий завершения |
| --- | --- | --- |
| 1 | Сроки синхронизации / предпочтительные пиры | Открыто. Нужны модель сроков Core, условия переходов, preferred-peer counters и независимые сценарии с управляемыми часами. Существующие пятисекундный live-предел и последовательный обход нельзя объявлять эквивалентными асинхронному планированию Core. |
| 2 | Начальная синхронизация только через ручные подключения | Реализовано для `maxconnections=0` с явным `manual-peers`: ожидание соединения, загрузка заголовков/блока, минимальная работа, live-sync и готовность майнинга без автоматических слотов. Настоящий P2P regtest-блок принят; отмена ожидания проверена. Полная сборка и рабочий перезапуск фиксируются отдельно. |
| 3 | Остальные разрешения пиров | Частично. [Addr](addr-permission-core32.md), [Relay/ForceRelay](relay-permissions-core32.md) и [Mempool/BIP35](mempool-permission-core32.md) имеют реальные обработчики и проверки. Для Mempool реализованы доступ, trickle, feefilter по базовой комиссии, MSG_TX/MSG_WTX и INV ≤50 000. Bloom, upload-target/GETDATA sequence и полная нативная wire-матрица остаются открытыми. |
| 4 | Оптимизатор mempool | Открыто. Требуются независимые сравнения промежуточных результатов при ограниченном бюджете Core, вставках/удалениях/reorg/изменении fees и равных ставках; действующие unlimited-reference случаи не закрывают этот пункт. |
| 5 | RPC | Частично. Добавлен `getpeerinfo.presynced_headers` с реальными PRESYNC/REDOWNLOAD/завершением. Нативная проверка выгружает точный список методов Core и ноды; схемы и семантика каждого отсутствующего node-метода остаются отдельной работой. Инвентарь включает wallet-методы и сам по себе не задаёт объём кошелька. |
| 6 | Дополнительные сети | Открыто. Нужны SOCKS/Tor, I2P SAM, private broadcast и внешний ASMap с локальными протокольными/интеграционными фикстурами. Подключение реальных внешних сервисов не требуется для завершения реализации и не включается автоматически. |
| 7 | Compact blocks | Открыто. Сначала нужен точный разбор условий оптимистичного кеша/объявлений и авторизации запросов относительно Core, затем независимые wire-сценарии для найденных отличий. Уже работающий кеш последнего принятого блока не подменяет этот объём. |
| 8 | Ошибки блоков/транзакций и смена источника | Открыто. Существующие coordinator/scheduler/reconnect/compact/relay тесты являются базой. Требуется таблица по классам ошибок и независимое сравнение: наказание/сохранение пира, повтор запроса, смена источника, состояние failed-ветки и отсутствие активации невалидных данных. |

Отчёт по текущей реализации ручной синхронизации и PRESYNC RPC:
[manual-bootstrap-presync-rpc-core32.md](manual-bootstrap-presync-rpc-core32.md).

Оставшиеся отличия: [core32-seven-workstreams.md](core32-seven-workstreams.md).

RPC inventory including hidden Core commands: 176 reference names, 52 local
names, 124 missing names and zero additional names. Categories and complete
lists: [core32-rpc-method-inventory.md](core32-rpc-method-inventory.md).
This is a name inventory, not schema/behavior verification.

Latest ordinary INV/base-fee batch: 5,225 full-reactor cases (5,213 passed,
12 skipped), zero failures/errors; both applications packaged. Original IDEA
GPU node PID 130828 reached RUNNING; height/headers 974688/974688, IBD=false,
eight connections, 37,866 mempool entries, accepted GPU shares and exactly one
Bitcoin process were verified. [Implementation and scope](ordinary-relay-base-fee-core32.md).
This closes the ordinary INV modified-fee/filter discrepancy, not the broader
budgeted optimizer or the other open directions.

Earlier Mempool/BIP35 batch: 5,224 full-reactor cases (5,212 passed, 12 skipped),
zero failures/errors, both applications packaged. Original IDEA GPU node PID
130884 reached RUNNING; height/headers 974686/974686, IBD=false, five peers,
31,848 mempool transactions, accepted CUDA shares and exactly one Bitcoin
process were verified. [Implementation and remaining scope](mempool-permission-core32.md).

Earlier Relay/ForceRelay batch: 5,215 full-reactor cases (5,203 passed, 12 skipped),
zero failures/errors, both applications packaged. Original IDEA GPU node
PID 131136 reached RUNNING; height/headers 974681/974681, IBD=false, seven peers,
29,899 mempool entries, accepted GPU shares and one Bitcoin process were verified.
See [implementation, checks and remaining scope](relay-permissions-core32.md).

Earlier ADDR batch: 5,207 full-reactor cases (5,195 passed, 12 skipped), zero
failures/errors, both applications packaged. Original IDEA GPU node PID 128836
reached RUNNING; RPC height/headers 974669/974669 and IBD=false were verified.
This completed the ADDR portion only. The later Relay/ForceRelay implementation
is described above; the later Mempool/BIP35 implementation is linked in the
direction 3 row. Bloom and complete native permission comparisons
remain open. See [ADDR implementation and verification](addr-permission-core32.md).

Manual/bootstrap verification baseline: 5,189 full-reactor cases (5,177 passed, 12 skipped), no
failures/errors, both applications packaged. The final hidden-method inventory
also passed the separate native Core test. Original single IDEA node restarted
as PID 126060 and verified RUNNING/IBD=false at height 974660. Thus direction 2's
implementation batch is verified; the other seven directions are not closed.

Direction 4's observed conflict-free restoration graph/copy bottleneck is now
repaired and passes allocation and 400-DAG differential checks; the complete
budgeted Core optimizer remains open. See
[implementation and remaining scope](mempool-admission-local-work-core32.md).

Further concrete differences repaired under directions 4/5: RBF checks prospective
cluster limits before diagram optimization, cluster size uses aggregate adjusted
weight, and the single-transaction RPC reports Core's too-large-cluster reason.
The native 65-transaction replacement scenario and graph boundary cases are
documented in [RBF/weight/RPC verification](rbf-cluster-preflight-core32.md).
The final source passed all 5,199 full-reactor cases (5,187 passed, 12 skipped),
including native Core's signed replacement scenario. Original IDEA GPU process
PID 129184 was verified RUNNING at height 974668 with IBD=false, 23,428 mempool
transactions, eight peers and GPU accepted shares. Direction 4's budgeted
optimizer and the broader open directions are still not closed by these fixes.

The mempool follow-up passed 5,191 full-reactor cases (5,179 passed, 12 skipped),
then all 506 mempool cases again after the final count-cap refinement. Both
applications repackaged successfully. Original IDEA GPU process PID 127816 was
verified RUNNING at height 974664 with IBD=false, 24,180 mempool entries and GPU
accepted shares. Observed initialization was 8.534 seconds versus 324.841 on the
prior run; workload differences preclude treating this as a controlled benchmark.
