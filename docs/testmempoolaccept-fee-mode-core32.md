# testmempoolaccept: индивидуальные комиссии

Эталон: [Bitcoin Core 32.1, PackageTestAccept и PackageChildWithParents](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L467).

Пробная проверка нескольких транзакций теперь сохраняет пакетный контекст
зависимостей, но проверяет minrelay и текущий rolling minimum для каждой
транзакции отдельно. Высокая комиссия потомка не оплачивает родителя в
testmempoolaccept. Приём submitpackage сохраняет пакетную CPFP-оплату.
Внутренняя копия mempool наследует rolling floor; проба не меняет рабочий пул.

RPC effective-feerate рассчитывается отдельно для каждой транзакции из её
modified fee и adjusted vsize с целочисленным округлением sat/kvB;
effective-includes содержит только её wtxid.

PackageProbeFeeModeTest проверяет пять сценариев: неоплаченный родитель,
неоплаченный потомок, две оплаченные транзакции, адресное действие fee delta
и живой rolling floor. BitcoinCore32CompatibilityTest создаёт независимо
подписанные родителя и потомка через настоящий Core 32.1, сравнивает результат,
vsize и весь объект fees для успешной пробы и неизменность обоих пулов.

Это исправление режима комиссий. Полное совпадение формы частичных отказов,
пропущенных потомков и всех причин ошибок RPC ещё не подтверждено.

Последующая [доводка формы пакета и submission preflight](package-probe-topology-core32.md)
снимает ограничение child-with-parents с testmempoolaccept и исправляет вызов
этой пробы из RPC submitpackage: фактическая отправка требует собственного
неизменяющего пул CPFP/RBF-preflight.

Проверка 6 ноября 2026: полная Bitcoin/RVN-сборка завершена в 16:14:06
Bangkok, 5 257 случаев: 5 245 пройдено, 12 opt-in пропущено, ошибок нет.
Все пять новых сценариев и нативное сравнение Core 32.1 выполнены.
Лог: `target/core-update-audit/core32-probe-fee-mode-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с профилями mainnet/gpu, PID 123980. Проверено RUNNING,
height=headers=974789, IBD=false, семь подключений (два block relay),
19 061 транзакция mempool, один Bitcoin-процесс и отсутствие привилегированных
пиров. CUDA принимает shares, в том числе после переподключения Stratum.
RPC-снимок: `target/core-update-audit/core32-probe-fee-mode-live-verification.json`;
записи RUNNING и shares: `target/mainnet-node.log`, PID 123980.
