# Проверка пользовательского списка различий Core 32.1

Исходный аудит — 11 ноября 2026; подтверждённые дополнения — 12–13 ноября. Эталон Core 32.1.
Таблица фиксирует весь запрошенный объём: отдельные успешные тесты не означают
завершения строки целиком. Новые RPC-заглушки не считаются реализацией методов.

Дополнение: [постоянный индекс основного mempool](persistent-main-graph-core32.md)
хранит зависимости и компоненты между операциями; повторные чтения используют
версию графа. Текущая реализация main/staging и бюджетного SFL описана в [отчёте приёмки](mempool-core32-completion.md); следующие дополнения сохраняют историю этапов.
Общий commit RBF/package/revalidate/reorg теперь также сохраняет известные связи:
[обновление индекса по разнице](graph-commit-delta-core32.md).
Начальное наполнение пробного/пакетного/reorg-пула теперь использует
[независимую копию готовых индексов](stage-graph-copy-core32.md), без повторного
чтения inputs всех сохранённых транзакций. Разделяемый Core staging и бюджетный
оптимизатор этим не закрыты.
Spent-index при общем commit также [обновляется по разнице](spent-commit-delta-core32.md),
без повторного чтения inputs незатронутых транзакций.
RPC-представления графа и замыкания admission-конфликтов также переведены на
[постоянный индекс](indexed-graph-queries-core32.md); в одном пакетном запросе
компонента линейзуется один раз. Полная схема/диагностика всех RPC ещё открыта.

| Направление | Подтверждённое состояние и незакрытый объём |
| --- | --- |
| Mempool и пакеты | Реализованы указанные восемь подпунктов: бюджетный SFL, main/staging, tie ordering, общие проверки, script diagnostics, частичный submitpackage, client caps и fee metadata. Дополнительно — memory accounting/pressure, reorg trim и expiry. [Критерии и независимые проверки](mempool-core32-execution.md), [сборка и рабочая приёмка](mempool-core32-completion.md). |
| RPC | Не завершено. Последний нативный инвентарь: 55 локальных имён из 176, 121 отсутствует, включая 58 wallet. Полные OpenRPC/help 55 методов и проверенные общие transport/arity/type/named/alias отказы теперь сопоставлены с Core: 493 сравнения без различий. Все предметные результаты и побочные эффекты этим не подтверждены. [Общий обработчик и границы](rpc-common-core32.md), [инвентарь](core32-rpc-method-inventory.md), [объём](core32-rpc-execution.md). |
| Синхронизация | Формулы сроков и строгие границы проверены. В этом исправлении добавлен учёт VERSION-only альтернатив до READY для outbound, manual и inbound, без передачи им чтения/загрузки. Нативный Core тест дополнен задержанным VERACK для обычного и NoBan источника. Полный scheduler и import/reindex остаются открыты. [Исправление](preferred-peer-handshake-gap-core32.md). |
| Разрешения пиров | Не завершено. NoBan/Download/Addr/Relay/ForceRelay/Mempool имеют реализацию и конкретные проверки. В NodeRelayService команды filterload/filteradd/filterclear только отклоняются при отсутствии NODE_BLOOM; это не Bloom/BIP37. Полные upload-target, GETDATA sequence и wire-матрица ещё открыты. [Статус](core32-eight-acceptance.md). |
| Дополнительные сети | Не завершено. Представление TORV3/I2P в адресах и netgroups не является транспортом. SOCKS/Tor, SAM, private broadcast и внешний ASMap отсутствуют в завершённом production-пути. Требуются реализации и локальные протокольные фикстуры, без обязательного включения внешних сервисов. |
| Compact blocks | Не завершено. Negotiated-version guard и кеш последнего принятого блока с проверкой индексов работают. Полные условия side-block optimistic cache, объявлений и разрешения запросов ещё не подтверждены независимо. [Общий объём](core32-seven-workstreams.md). |
| Ошибки и смена источника | Не завершено. Проверены отдельные initial/live header reject/NoBan/transport/local-state сценарии, включая сохранение внутренних locator-ошибок. Полная таблица block/transaction punishments, retries, failover и invalid branches не построена. [Последняя граница ошибок](availability-failure-boundary-core32.md). |

Следующие независимые критерии приёмки для каждого изменения: фактическое
production-поведение, проверка позитивных и отказных путей, сравнение с эталоном
там, где заявлено соответствие Core, полная сборка и проверка одной исходной
IDEA GPU-ноды. Непроверенные сочетания остаются открытыми.

Последующее уточнение RPC: выровнены decode/count сообщения и порядок ранних
проверок размера перед fee/burn в обоих пакетных методах. Нативная матрица
содержит 26 новых сравнений, включая отсутствие приёма корректного префикса
перед повреждённой строкой. [Проверки и границы](package-rpc-decoding-core32.md).

Денежные параметры трёх transaction RPC и ранний sendrawtransaction:
исправлены точные ошибки, границы fee/range и порядок burn/decode/fee;
[159 нативных сравнений и оставшийся объём](rpc-amount-limits-core32.md).

Следующее уточнение sendrawtransaction: base-fee cap с округлением вверх и
повтор известного txid без повторного admission, с принятым witness.
[Нативные сценарии и оставшийся объём](rpc-sendraw-fee-cap-core32.md).

Классификация отсутствующих входов (-25) и подтверждённых выходов (-27)
sendrawtransaction сопоставляется по полным ответам с Core;
[реализация и границы](rpc-broadcast-classification-core32.md).

Проверка и приём sendrawtransaction объединены под chain monitor, включая
регистрацию unbroadcast; устранено окно изменения состояния между фазами.
[Конкурентная регрессия и нативные проверки](atomic-broadcast-core32.md).

При отказе broadcast preflight обычный admission больше не повторяется;
сохраняется исходное исключение, настоящий pool не меняется.
[Реализация и пределы](broadcast-preflight-core32.md).

Ранние broadcast-результаты больше не запускают expiry настоящего pool;
проверены graph/spent/sequence/unbroadcast для старой семьи parent/child.
[Четыре воспроизведённых сценария и исправление](broadcast-expiry-core32.md).

Устранено усечение дробей/переполнение fee_delta в prioritisetransaction;
исправлены dummy, проверенные ошибки типов и named-аргументы.
[Нативная матрица и ограничения](rpc-priority-arguments-core32.md).

Нулевой fee_delta принятого dust-родителя теперь отклоняется, как в Core
при стандартной политике; проверены также +/-1 и неизменность графа.
[Строгая native-фикстура и контроль обычного child](dust-priority-core32.md).

Равные ставки: исправлен общий ключ графа/майнинга и отдельный ordinal fallback
TransactionSelector. Совпадение порядка реальных подписанных транзакций проверено
в Core GBT, Java graph order и Java GBT. Прежнее описание Core fallback как
внутреннего transaction index было ошибочным: production Core использует txid.
[Доказательства и оставшиеся optimizer/graph/eviction отличия](equal-rate-fallback-core32.md).

Eviction теперь использует обратный Core chunk key, а не первый встретившийся
tail при равной ставке. Семь регрессий проверяют состав пула, новый худший chunk
и округлённую removed fee rate. Прямой native pressure-тест ещё открыт.
[Сопоставление и ограничения](equal-rate-eviction-core32.md).
