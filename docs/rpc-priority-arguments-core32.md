# prioritisetransaction: точность аргументов

Эталон: [официальный Core 32.1 mining.cpp](https://github.com/bitcoin/bitcoin/blob/v32.1/src/rpc/mining.cpp#L495),
нативный bitcoin-32.1 и сохранённый OpenRPC-каталог.

Исправлены усечения Number.longValue(): дробный dummy больше не считается
нулём, fee_delta разбирается как точный int64 без отбрасывания дроби и
переполнения. Неверное целое возвращает Core -1. В проверенных случаях
ненулевой dummy даёт точный Core -8.

Проверка типов выдаёт структурированный -3 с позицией/именем и несколькими
ошибками сразу. Общий number/string helper сохраняет прежние chain-wait
проверки. Txid проверяется общим Core hash parser, включая длину UTF-8.
В registry dummy исправлен на optional number: named arguments могут его
пропустить, а null остаётся допустимым.

Нативная матрица расширена 35 сравнениями: неверные типы, дроби, JSON exponent
формы, выход за int64, ненулевой dummy, плохой txid и смешанные ошибки,
включая named arguments. После всех отказов таблицы prioritisations каждого
узла должны точно совпадать со своими исходными снимками.

Первые прогоны уточнили структурированный формат -3 и точное сообщение -8.
Финальная проверка: 59 выбранных случаев, 0 failures/errors/skips,
12 ноября 2026, 14:58:22 Bangkok;
`target/core-update-audit/core32-priority-arguments-focused-complete.log`.
Это существующий нативный JUnit-тест с 35 дополнительными строками матрицы,
не 35 новых JUnit cases и не повтор полного reactor 5378.

Arity/help, вся вложенная схема OpenRPC, экстремальные double dummy,
накопительное переполнение fee deltas и полная dust-матрица ещё не закрыты.
[Оставшийся объём](core32-user-checklist.md).
Принятый dust-родитель с нулевым delta впоследствии проверен и исправлен;
[строгая политика Core и регрессии](dust-priority-core32.md).

Bitcoin/RVN упакованы с `-DskipTests` после проверок, 14:59:04 Bangkok;
`target/core-update-audit/core32-priority-arguments-package.log`.
Одна исходная IDEA GPU-нода PID 33472: RUNNING, blocks=headers=975530,
IBD=false, 6 пиров (2 block relay), 36004 mempool entries, 0 привилегированных
пиров, принятые CUDA shares. RPC-снимок 15:00:23:
`target/core-update-audit/core32-priority-arguments-live.json`.
