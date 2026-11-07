# Дедупликация witness в submitpackage

Эталон: [Core 32.1 AcceptPackage](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L1510).

При совпадении txid submitpackage сохраняет уже принятую транзакцию, даже если
переданный witness отличается. Пакет не отклоняется из-за этого отличия:
RPC сообщает other-wtxid сохранённой версии под ключом переданного wtxid.
Переданный witness не проверяется и не заменяет принятый, как в Core.

Предварительная проверка одноэлементного submitpackage теперь также использует
дедупликацию пакетного приёма вместо одиночного admit. testmempoolaccept
сохраняет отдельное поведение: известная транзакция отклоняется.
Relay и пересмотр orphan-потомков получают принятые записи, а не исходный
массив пользователя: альтернативный непринятый witness не объявляется сети.

SubmissionWitnessDedupTest проверяет точный одноэлементный повтор, альтернативный
witness без замены и новый ребёнок при альтернативном witness известного
родителя. Проверяются сохранение исходной записи и неизменность пула при пробе.
Нативный BitcoinCore32CompatibilityTest сравнивает package_msg и весь tx-results
для точного/изменённого вариантов уже известной транзакции с Core 32.1,
проверяя неизменность обоих mempool.

Объём — дедупликация witness, не полное соответствие submitpackage: частичные
результаты, отдельный приём родителей, общая матрица ошибок, клиентские лимиты
и метаданные effective-feerate новых транзакций ещё требуют доводки.

Проверка 7 ноября 2026: полная Bitcoin/RVN-сборка завершена в 02:13:35
Bangkok: 5 287 случаев, 5 275 пройдено, 12 opt-in пропущено, ошибок нет.
Три новых mempool-сценария и нативный Core 32.1 тест выполнены,
оба приложения собраны. Логи:
`target/core-update-audit/core32-submission-witness-focused.log`,
`target/core-update-audit/core32-submission-witness-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 138876. Проверено RUNNING,
height=headers=974843, IBD=false, восемь подключений (одно block relay),
29 827 записей mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-submission-witness-live-verification.json`.
