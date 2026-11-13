# RPC: денежные параметры и ранние отказы sendrawtransaction

Эталон: сохранённые Core 32.1 `core32-rpc-util-reference.cpp` (AmountFromValue,
ParseFeeRate) и `core32-rpc-mempool-reference.cpp` (три transaction RPC).

Общий денежный разбор теперь различает:

- Не number/string: RPC -3, `Amount is not a number or string`.
- Неверный fixed-point формат, дробный сатоши или модуль масштабированной
  суммы от 10^18: RPC -3, `Invalid amount`.
- Отрицательную целую сумму либо превышение MAX_MONEY: RPC -3,
  `Amount out of range`.
- maxfeerate от 1 BTC/kvB включительно: RPC -8,
  `Fee rates larger than or equal to 1BTC/kvB are not accepted`.

Раньше Java принимала maxfeerate=1, возвращала другие сообщения и не проверяла
MAX_MONEY отдельно от переполнения long. BigDecimal без проверки синтаксиса
также принимал форматы вроде ведущего плюса и нуля. Число переводится в сатоши
без округления; scientific notation и незначащие конечные нули допускаются
при точной целой сумме. Предельные значения проверяются до преобразования в long.

sendrawtransaction теперь выполняет порядок Core: maxburnamount, decode,
проверка каждого burn-выхода, maxfeerate, приём. Decode возвращает -22 с
одиночной Core-подсказкой. OP_RETURN и скрипты с невалидными операциями
проверяются до validation/приёма; превышение суммы нескольких выходов само
по себе не является превышением пользовательского лимита на один выход.
В пакетных RPC сохраняется их собственный порядок: count, fee, затем decode
и burn каждого элемента (для submitpackage).

В BitcoinCore32CompatibilityTest добавлены 159 сравнений полных error-объектов:
135 сочетаний денежных аргументов с повреждённым hex по пяти путям параметров,
21 отказ maxfeerate на корректно закодированном родителе и 3 burn/fee сценария.
Они включены в существующий JUnit-тест, а не увеличивают число его test cases.
Первый промежуточный прогон зафиксировал несовпадение одиночного decode-отказа;
порядок и сообщение исправлены по нативному Core.

Остальные методы RPC, полная матрица fixed-point экспонент/мантисс и весь
mempool optimizer этим исправлением не закрыты.
[Остальной объём](core32-user-checklist.md).

Выборочная проверка: 17 случаев, все успешны, 0 skipped/failures/errors;
`target/core-update-audit/core32-rpc-amount-focused-final.log`,
12 ноября 2026, 01:03:47 Bangkok. Включены BitcoinCore32CompatibilityTest,
MiningRpcTest, PartialPackageSubmissionTest. Полный reactor 5378 случаев
этого конкретного изменения повторно не выполнялся.

Bitcoin/RVN упакованы с `-DskipTests` после выборочной проверки:
`target/core-update-audit/core32-rpc-amount-package.log`, 01:04:33 Bangkok.
Одна исходная IDEA GPU-нода PID 114048 запущена после сборки. RPC-снимок
01:05:23: blocks=headers=975461, IBD=false, 5 пиров (2 block relay), 39937
mempool entries, 0 привилегированных пиров. Журнал подтверждает RUNNING
в 01:05:25 и последующие принятые CUDA shares.
Снимок: `target/core-update-audit/core32-rpc-amount-live.json`.
