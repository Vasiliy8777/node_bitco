# RPC пакетов: декодирование и порядок ранних отказов

Эталон: `target/core-update-audit/core32-rpc-mempool-reference.cpp`, обработчики
`testmempoolaccept` и `submitpackage` Bitcoin Core 32.1.

Исправлены три различия:

- Ошибка массива вне диапазона 1–25 теперь точно содержит завершающую точку:
  `Array must contain between 1 and 25 transactions.` (код -8).
- Проверка количества предшествует чтению maxfeerate/maxburnamount. Пустой
  или слишком большой пакет не маскируется ошибкой неверной комиссии.
- Ошибка декодирования содержит проблемную rawtx-строку и подсказку Core:
  `TX decode failed: <hex> Make sure the tx has at least one input.` (код -22).
  Общее декодирование обоих методов также отклоняет транзакции без входов.

При submitpackage проверка burn остаётся сразу после декодирования каждой
транзакции, до следующей rawtx и до приёма в mempool. Успешно декодированный
префикс не принимается, если следующая строка не декодируется.

Нативная дифференциальная матрица добавлена в BitcoinCore32CompatibilityTest:
пустая строка, усечённый hex, не-hex, пустой witness/input encoding; каждый
вариант отдельно и после настоящего родителя. Размер 0/26 проверяется сам по
себе и с неверными fee/burn параметрами. Сравниваются целые error-объекты и
неизменность обоих mempool.

Это отдельный участок RPC-диагностики. Он не закрывает верхнеуровневые типы
параметров, все варианты DecodeHexTx, общую матрицу amount-параметров, script diagnostics,
бюджетный оптимизатор и остальные методы RPC.
[Полный список оставшейся работы](core32-user-checklist.md).

Выборочный reactor прошёл 17 тестов без ошибок и пропусков, включая
BitcoinCore32CompatibilityTest, MiningRpcTest и PartialPackageSubmissionTest.
В существующий нативный тест включены 26 новых сравнений ранних RPC-отказов;
это 26 строк матрицы, а не 26 дополнительных JUnit-тестов.
Журнал: `target/core-update-audit/core32-package-rpc-decode-focused.log`,
11 ноября 2026, 22:17:36 Bangkok.

Первый полный прогон (`core32-package-rpc-decode-full-build.log`) прошёл новые
decode-сравнения, но остановился на последующей TRUC-фикстуре: нативный
sendrawtransaction вернул insufficient fee для неожиданной замены. Фикстура
выбирала монету из wallet listunspent после серии submitpackage без ожидания
асинхронных validation callbacks. Она теперь вызывает
syncwithvalidationinterfacequeue и проверяет gettxout с include_mempool=true,
выбирая действительно свободный вход для независимой семьи. Требование
успешного приёма и все сравнения TRUC сохранены; поведение production mempool
для устранения этого сбоя не менялось.

Повторный полный Bitcoin/RVN reactor завершён успешно 11 ноября 2026,
22:31:37 Bangkok: 5378 случаев, 5366 успешных, 12 пропущенных, 0 failures/errors;
обе программы упакованы. Журнал:
`target/core-update-audit/core32-package-rpc-decode-full-build-final.log`.
Нативный RPC/TRUC тест в этом прогоне прошёл за 84.93 секунды.

После сборки запущена одна исходная IDEA GPU-нода PID 90688. Проверка 22:32:25:
RUNNING, blocks=headers=975450, IBD=false, 8 пиров (2 block relay), 36577
mempool entries, 0 привилегированных пиров. Журнал GPU подтверждает accepted=6
и последующие принятые shares. Снимок:
`target/core-update-audit/core32-package-rpc-decode-live.json`.

Следующее исправление: нестроковые элементы обоих массивов теперь возвращают
Core RPC -3 с `JSON value of type <type> is not of expected type string`,
вместо прежнего -32602 и общей фразы о hex. Имена типов: null, number, bool,
array, object. Проверка остаётся последовательной: неверный тип не маскирует
ошибку hex в предыдущем элементе, а неверный размер массива обнаруживается
до проверки его элементов.

В нативную матрицу добавлены 50 сочетаний: пять типов, по пять контекстов
для каждого из двух методов — отдельно, после корректного родителя, после
плохого hex, перед плохим hex, массив из 26 таких элементов. Проверяется
неизменность mempool, включая отсутствие приёма корректного префикса.
Первая попытка выявила точное имя типа `bool`; production-диагностика
скорректирована по реальному ответу Core.

Проверка типов завершена 11 ноября 2026, 23:47:21 Bangkok: 17 выбранных тестов
без failures/errors/skips, включая полную существующую нативную RPC/пакетную
матрицу и 50 новых сочетаний. Журнал:
`target/core-update-audit/core32-package-rpc-element-types-focused-final.log`.
Bitcoin/RVN упакованы в 23:48:06 с `-DskipTests` после этих проверок;
`target/core-update-audit/core32-package-rpc-element-types-package.log`.
Это выборочная проверка последующего изменения, не новый полный прогон 5378.

Исходная IDEA GPU-нода PID 139888: один Bitcoin-процесс, RUNNING,
blocks=headers=975453, IBD=false, 4 пира (2 block relay), 42379 mempool entries,
0 привилегированных пиров; принятые CUDA shares подтверждены журналом.
Снимок 23:48:47: `target/core-update-audit/core32-package-rpc-element-types-live.json`.
