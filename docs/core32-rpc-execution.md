# Выполнение RPC-совместимости с Core 32.1

Инвентарь имён и реализация методов не доказывают полного совпадения схем,
параметров, ошибок и побочных эффектов. Подмена отсутствующих методов
заглушками не считается завершением.

| Область | Состояние |
| --- | --- |
| Каталог методов | Из 176 эталонных имён реализованы 74; отсутствует 102 имён. Добавлены getblockstats, transaction proofs, submitheader и три метода создания транзакций/PSBT; полный список в [инвентаре](core32-rpc-method-inventory.md). |
| Схемы и help | Полные OpenRPC-записи и help всех 74 существующих методов сравниваются с работающим Core. `show_hidden` различает 71 публичных и 74 полных записей. Совпадение описаний не подтверждает все предметные результаты. [Общий обработчик](rpc-common-core32.md), [новые методы](rpc-proof-header-core32.md). |
| Причины и детали ошибок | Общие arity/type/named/alias отказы 74 методов и протокол охвачены нативной матрицей из 647 сравнений. Дополнительно — 646 сравнений transaction proofs и 30 submitheader. Для chain-wait проверены диапазоны, hash и приоритеты. Полная предметная матрица RPC ещё открыта. |
| Реальное ожидание цепочки | Добавлены timeout/current_tip, уведомления активной цепочки, reorg и shutdown; [проверки](rpc-chain-wait-core32.md). |
| Остальные node RPC | Blockchain, control, network, raw transaction, util, hidden/regtest и ZMQ методы инвентаря ещё требуют реализации/сопоставления. |
| Кошелёк и signer | В полном инвентаре есть 58 отсутствующих wallet-методов и enumeratesigners. Полноценный кошелёк входит в запрос; эти имена не исключены из общего счётчика. |
| Transport/dispatch | Исправлены проверенные HTTP/JSON-RPC конверты, batch, notifications, named args/aliases и общие ошибки; транспорт работает внутри одной ноды. Полные framing/header/resource/startup/warmup/ACL/configuration матрицы ещё открыты. [Реализация и границы](rpc-common-core32.md). |

Отдельный блок mempool/приёма пакетов прошёл текущую приёмку:
[реализация, независимые проверки и рабочий запуск](mempool-core32-completion.md).

Пакетные RPC: выровнены сообщения decode/count и приоритет размера массива
перед fee/burn. Нативная матрица сравнивает полные error-объекты и отсутствие
приёма корректного префикса перед повреждённой rawtx.
[Реализация и границы](package-rpc-decoding-core32.md).
Также проверены 50 сочетаний неверных типов элементов: код -3, точные имена
типов Core и приоритет перед/после decode-ошибки. Полная поверхность RPC
по-прежнему остаётся незавершённой.

Денежные параметры: исправлены fixed-point/type/range отказы и граница
maxfeerate >= 1 BTC/kvB. sendrawtransaction проверяет burn до decode/fee
в порядке Core, по каждому выходу. Добавлены 159 нативных сравнений.
[Реализация, проверка и пределы](rpc-amount-limits-core32.md).

sendrawtransaction: cap использует base fee и округление CFeeRate вверх по
BIP141 vsize; повтор известного txid объявляет сохранённый witness без нового
admission. Сравнены обе fee delta и соседние ставки на границе cap.
[Проверки и оставшиеся сценарии](rpc-sendraw-fee-cap-core32.md).

Классификация sendrawtransaction: типизированное отсутствие входов даёт -25,
найденный выход в цепном UTXO — -27 до admission. Добавлено девять сравнений
нативных отказов с различными cap.
[Детали и оставшийся объём](rpc-broadcast-classification-core32.md).

Проверка и приём локального broadcast объединены под chain monitor:
UTXO/known/cap/admission/unbroadcast теперь используют общую критическую секцию.
[Регрессия конкурентного изменения fee delta](atomic-broadcast-core32.md).

Отказ broadcast preflight немедленно возвращается исходным исключением;
повторный admission при неуспешной пробе устранён.
[Изолированная проба и проверки отсутствия побочных эффектов](broadcast-preflight-core32.md).

Удалён ранний expiry настоящего pool из broadcast: cap/confirmed/missing
отказы и повтор known txid сохраняют старый граф и unbroadcast.
[Clock-регрессия до/после исправления](broadcast-expiry-core32.md).

prioritisetransaction: точный int64 fee_delta, ненулевой дробный dummy,
структурированные type errors и optional dummy для named arguments.
[35 нативных сравнений и оставшиеся ограничения](rpc-priority-arguments-core32.md).

Для принятого dust-txid prioritisetransaction теперь отклоняет и нулевой delta
с точным сообщением Core. Отдельная строгая native-фикстура проверяет родителя
и положительный контроль на ребёнке без dust.
[Сравнение и пределы](dust-priority-core32.md).

[Чтение блоков и транзакций](rpc-block-read-core32.md): 979 нативных сравнений и 1 251 сравнение script/decode в трёх сетях.
[Создание транзакций и PSBT](rpc-transaction-creators-core32.md): 20 571 нативное сравнение в трёх сетях.

[Multisig и сообщения](rpc-multisig-message-core32.md): 9 942 и 1 887 нативных сравнений. Расширенная общая script/Creator матрица: 1 371 и 21 051 сравнение в трёх сетях.

[Разбор, объединение и соединение PSBT](rpc-psbt-documents-core32.md): 7 387 / 8 983 / 6 431 нативное сравнение; три метода проверены по HTTP в трёх сетях. Updater/signer/finalizer ещё открыты.

Finalizer/Extractor PSBT, Miniscript и публичная агрегация MuSig2: [реализация и независимые проверки](rpc-psbt-finalizer-core32.md).

Все три util-метода и decodescript: [реализация и нативная матрица](rpc-descriptor-utility-core32.md). Анализ PSBT: [analyzepsbt](rpc-psbt-analyzer-core32.md).
