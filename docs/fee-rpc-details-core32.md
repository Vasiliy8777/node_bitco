# Числовые reject-details комиссионных отказов

Эталон: [Core 32.1 CheckFeeRate](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L643).

MempoolFeeException передаёт структурированную причину комиссии и значения
paid/required отдельно от внутреннего диагностического сообщения.
TestAcceptResult сохраняет готовое rejectDetails; RPC не извлекает числа из
строки исключения. Для одиночной пробы возвращается Core-формат:
min relay fee not met, paid < required либо mempool min fee not met, paid < required.

Существующие конструкторы TestAcceptResult сохранены. Остальные причины
отказа не получают автоматически придуманные детали. Внутренние сообщения
minrelay и mempool-min-fee, ставки и решения о приёме не изменяются.

FeeFloorPriorityTest проверяет оба набора чисел, включая -1 modified fee,
локально поднятый rolling floor и неизменность пула. Нативный
BitcoinCore32CompatibilityTest теперь сравнивает целые RPC-объекты для
нулевой комиссии с невалидной подписью и отрицательной modified fee;
ранее в этих сценариях сравнивалось только reject-reason.

Объём — детали одиночных комиссионных отказов. Пакетные частичные результаты,
другие классы ошибок, отдельное поднятие rolling floor настоящего Core и
полная фазовая модель admission остаются открытыми.

Проверка 7 ноября 2026: усиленные локальные и нативные проверки прошли.
Полная Bitcoin/RVN-сборка завершена в 19:28:13 Bangkok:
5 310 случаев, 5 298 пройдено, 12 opt-in пропущено, ошибок нет;
оба приложения собраны. Лог:
`target/core-update-audit/core32-fee-details-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 73584. Проверено RUNNING,
height=headers=974932, IBD=false, шесть подключений (два block relay),
33 988 записей mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-fee-details-live-verification.json`.
