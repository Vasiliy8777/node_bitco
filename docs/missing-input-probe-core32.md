# Отсутствующие входы в testmempoolaccept

Эталон: [Core 32.1 testmempoolaccept](https://github.com/bitcoin/bitcoin/blob/v32.1/src/rpc/mempool.cpp)
и [AcceptMultipleTransactionsInternal](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L1300).

Отсутствующий UTXO и несуществующий выход известного mempool-родителя имеют
отдельный внутренний тип ошибки. Классификация не зависит от текста сообщения.
В testmempoolaccept они отображаются как missing-inputs.

Для многотранзакционной пробы сохраняется индекс отказа. RPC возвращает
allowed=false и reject-reason=missing-inputs только для отказавшей записи,
без reject-details и package-error. Остальные записи содержат txid/wtxid
без allowed: пакет не завершил проверку, даже если входы предыдущих транзакций
уже были доступны. Изолированная staged-копия не попадает в рабочий пул.

MissingInputProbeTest проверяет одиночный отказ, позиции 0/1/2, доступность
внутрипакетного родителя до более позднего отсутствующего входа и неверный
номер выхода уже известного родителя. Нативный BitcoinCore32CompatibilityTest
сравнивает полные JSON-ответы Core/Java для одиночной записи и трёх вариантов
пакета; оба mempool остаются неизменными.

Объём — классификация и RPC-форма missing-inputs. Полное разделение всех
PreChecks/script checks на фазы, приоритет одновременно нарушенных правил,
остальные частичные отказы и submitpackage остаются отдельной работой.

Проверка 7 ноября 2026: три новых mempool-сценария и нативный Core 32.1 тест
прошли. Полная Bitcoin/RVN-сборка завершена в 04:25:02 Bangkok:
5 292 случая, 5 280 пройдено, 12 opt-in пропущено, ошибок нет;
оба приложения собраны. Логи:
`target/core-update-audit/core32-missing-input-probe-focused.log`,
`target/core-update-audit/core32-missing-input-probe-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 25008. Проверено RUNNING,
height=headers=974860, IBD=false, девять подключений (два block relay),
27 161 запись mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-missing-input-probe-live-verification.json`.
