# Имена одиночных RPC-отказов по комиссии

Эталон: [Core 32.1 CheckFeeRate](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L643).

Для одиночного testmempoolaccept локальные причины преобразуются в Core-имена:
Minimum relay fee not met: … → min relay fee not met;
mempool-min-fee → mempool min fee not met.
Внутренние исключения, детали диагностик и условия приёма не изменяются.
Преобразование не применяется произвольно ко всем пакетным ошибкам.

MiningRpcTest проверяет обе комиссии и существующие cluster-limit причины.
Нативный BitcoinCore32CompatibilityTest теперь требует совпадение reject-reason
для нулевой комиссии с повреждённой подписью и отрицательной modified fee;
ранее проверялся только класс/приоритет отказа с различающимися именами.

Объём — поле reject-reason одиночной пробы. reject-details, числовые детали,
общие и частичные ошибки пакета, клиентские лимиты остальных RPC и полный
порядок отказов остаются отдельными направлениями доводки.

Проверка 7 ноября 2026: новые RPC-сценарии и нативное сравнение имён Core 32.1
прошли. Полная Bitcoin/RVN-сборка завершена в 18:49:56 Bangkok:
5 310 случаев, 5 298 пройдено, 12 opt-in пропущено, ошибок нет;
оба приложения собраны. Лог:
`target/core-update-audit/core32-fee-reason-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 50404. Проверено RUNNING,
height=headers=974930, IBD=false, шесть подключений (два block relay),
29 771 запись mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-fee-reason-live-verification.json`.
