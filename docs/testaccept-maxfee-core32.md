# maxfeerate в testmempoolaccept

Эталон: [Bitcoin Core 32.1 testmempoolaccept](https://github.com/bitcoin/bitcoin/blob/v32.1/src/rpc/mempool.cpp)
и [CFeeRate::GetFee](https://github.com/bitcoin/bitcoin/blob/v32.1/src/policy/feerate.cpp).

Ограничение maxfeerate теперь сравнивается с base fee транзакции, а не с
modified fee после prioritisation. Предельная сумма вычисляется через
FeeRate.feeForVSize с округлением вверх до сатоши. Прежнее сравнение двух
произведений отвергало допустимую граничную сумму и могло переполняться.

Ответ формируется по порядку: предшествующие успешные результаты сохраняются,
первая превысившая лимит транзакция получает allowed=false и reject-reason
max-fee-exceeded, следующие записи содержат только txid/wtxid. Ошибка лимита
отдельной транзакции больше не превращается в общий package-error.
Нулевой maxfeerate отключает этот лимит.

TestAcceptMaxFeeRpcTest проверяет шесть комбинаций base fee, delta и лимита,
а также успешную первую запись, отказ второй и пропуск третьей. Нативный
BitcoinCore32CompatibilityTest сравнивает полные ответы с Core 32.1 для
положительной delta родителя, отрицательной delta ребёнка, граничного
округления и пропуска записей после первого отказа. Обе delta затем отменяются;
содержимое обоих mempool остаётся неизменным.

Объём — лимит testmempoolaccept после успешной mempool-пробы. Приоритет этого
лимита относительно иных ошибок отдельных транзакций, все частичные отказы
ATMP и клиентские лимиты submitpackage/sendrawtransaction требуют отдельной
проверки. Это не изменение правил консенсуса блоков.

Проверка 6 ноября 2026: семь новых RPC-сценариев и нативный Core 32.1 тест
прошли. Полная Bitcoin/RVN-сборка завершена в 20:33:17 Bangkok:
5 280 случаев, 5 268 пройдено, 12 opt-in пропущено, ошибок нет;
оба приложения собраны. Логи:
`target/core-update-audit/core32-maxfee-probe-focused.log`,
`target/core-update-audit/core32-maxfee-probe-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 6160. Проверено RUNNING,
height=headers=974810, IBD=false, шесть подключений (одно block relay),
36 005 записей mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-maxfee-probe-live-verification.json`.
