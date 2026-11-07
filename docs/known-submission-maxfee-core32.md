# maxfeerate для известных записей submitpackage

Эталон: [Core 32.1 AcceptPackage](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L1510).
Известные транзакции дедуплицируются до проверки клиентского maxfeerate новых
транзакций. Повтор уже принятой записи не должен отклоняться из-за лимита,
который пользователь указал при повторной отправке.

Результат изолированной submission-пробы теперь содержит неизменяемый набор
knownSubmissionTxids из исходного состояния mempool под его монитором.
RPC пропускает повторную maxfeerate-проверку только для этих записей;
новые родители и дети не получают исключения. Burn-проверка сохраняется.
Поддерживаются точное совпадение и альтернативный witness с тем же txid.

SubmissionWitnessDedupTest проверяет состав набора для одиночной известной
транзакции, альтернативного witness и известного родителя с новым ребёнком.
MiningRpcTest проверяет обычную отправку, повтор известного пакета с лимитом
1 sat/kvB и отказ новой транзакции при таком же лимите до relay.
Нативный BitcoinCore32CompatibilityTest сравнивает package_msg и весь tx-results
с Core 32.1 при этом лимите для точного и изменённого witness известной записи;
содержимое обоих mempool не меняется.

Проверка формы пакета также сопоставлена: RPC Core использует
IsChildWithParentsTree, поэтому действующее ограничение независимости родителей
сохранено. Один только внутренний AcceptPackage Core использует более слабую
IsChildWithParents; его нельзя переносить на публичный RPC без внешней проверки.

Открыты частичные результаты submitpackage, лимит для новых транзакций и его
форма ошибок, арифметика при больших значениях и полная CPFP/RBF-матрица.
Этот пакет изменений не закрывает эти направления.

Проверка 7 ноября 2026: целевые проверки и нативный Core 32.1 тест прошли.
Полная Bitcoin/RVN-сборка завершена в 03:25:34 Bangkok:
5 289 случаев, 5 277 пройдено, 12 opt-in пропущено, ошибок нет;
оба приложения собраны. Логи:
`target/core-update-audit/core32-known-submission-cap-focused.log`,
`target/core-update-audit/core32-known-submission-cap-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 91048. Проверено RUNNING,
height=headers=974852, IBD=false, семь подключений (два block relay),
28 630 записей mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-known-submission-cap-live-verification.json`.
