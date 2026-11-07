# Приоритет rolling minimum и minrelay

Эталон: [Core 32.1 CheckFeeRate](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L643).
Положительный mempoolRejectFee проверяется до minrelay. Нулевой rolling floor
не должен сам вызывать отказ, даже если modified fee отрицательна.

Mempool.admitInternal сначала вычисляет rolling minimum для adjusted vsize,
проверяет его при положительной сумме, затем вызывает minrelay-проверку.
Отказ dust-fee precheck также происходит до этих порогов, как в Core.
Ставки, округление и условия оплаты пакета не изменяются.

FeeFloorPriorityTest проверяет нулевую/отрицательную fee при поднятом floor,
сохранение rolling state и пула после отказа, ту же причину пакетной пробы,
успешный CPFP submission, отрицательную fee при нулевом rolling floor и
приоритет dust-with-fee. Floor поднимается реальным локальным вытеснением.

Объём — приоритет этих проверок. Текст mempool-min-fee ещё отличается от Core,
вложенные RPC-детали и полная матрица ошибок остаются открытыми. Нативный
reactor-тест проверяет общую регрессию Core 32.1; специальный сценарий с
принудительным поднятием floor настоящего Core этим тестом не реализуется.

Проверка 7 ноября 2026: четыре новых сценария и нативная регрессия Core 32.1
прошли. Полная Bitcoin/RVN-сборка завершена в 18:01:25 Bangkok:
5 308 случаев, 5 296 пройдено, 12 opt-in пропущено, ошибок нет;
оба приложения собраны. Логи:
`target/core-update-audit/core32-fee-floor-priority-focused.log`,
`target/core-update-audit/core32-fee-floor-priority-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 64748. Проверено RUNNING,
height=headers=974926, IBD=false, семь подключений (два block relay),
23 688 записей mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-fee-floor-priority-live-verification.json`.
