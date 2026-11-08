# Общие проверки повторного CPFP-приёма

Эталон — Bitcoin Core 32.1 `AcceptMultipleTransactionsInternal` и
`CheckEphemeralSpends`. Копии исходников:
`target/core-update-audit/btc-validation32.cpp` и
`target/core-update-audit/core32-ephemeral-policy-reference.cpp`.

При повторной пакетной оценке RPC submitpackage скрипты больше не исполняются
во время предварительного добавления отдельных родителей в staging pool.
Выполняются все индивидуальные PreChecks и client caps, затем пакетная
TRUC-проверка, общая комиссия, пакетный RBF, cluster limits и ephemeral spends.
После этого проверяются скрипты на сохранённых входах и выполняется commit.
Временные выходы непроверенных родителей не попадают в рабочий mempool.
Индивидуальная TRUC-проверка видит исходный пул.

Следствия:

- Поздний missing-inputs сохраняет первоначальную fee-ошибку родителя,
  даже если подпись родителя невалидна.
- Низкая общая комиссия возвращается на ребёнке до выполнения скриптов.
- Shared cluster failure имеет package_msg `too-large-cluster` и сохраняет
  индивидуальные результаты, а не подменяет их ранним отказом родителя.
- Ephemeral failure имеет package_msg `unspent-dust`; ребёнок получает
  `missing-ephemeral-spends` с txid/wtxid и сообщением Core. То же подробное
  сообщение используется в одиночной/пакетной пробе при данном отказе.
- Скриптовый отказ не фиксирует успешную часть повторного CPFP-пакета.
  Уже принятые индивидуально транзакции по-прежнему сохраняются.
- Общая комиссия retry включает все пересматриваемые записи, включая
  родителей, отклонённых по RBF, а не только родителей ниже fee floor.

`SharedSubmissionPhaseTest` проверяет missing-inputs vs bad script,
aggregate fee vs bad script, cluster priority/package state, ephemeral vs
bad script и отсутствие повторного чтения внешних монет при исполнении.
Нативный `BitcoinCore32CompatibilityTest` сравнивает полные RPC-ответы и
неизменность обоих пулов в пяти сценариях отказа, сохраняя прежние проверки
успешного CPFP и метаданных комиссий.

Оставшийся объём: точные debug-сообщения всех TRUC/RBF/consensus-ошибок,
приоритеты отдельных TRUC/client cap и ранних RBF-условий, сложные eviction/
пересекающиеся замены и полная таблица ScriptError. Бюджетный оптимизатор,
постоянный граф и равные ставки остаются отдельными открытыми направлениями.

Проверено 8 ноября 2026: полный reactor Bitcoin/RVN — 5 333 случая,
5 321 успешно, 12 пропущено, ноль failures/errors; оба приложения собраны.
Лог: `target/core-update-audit/core32-shared-submission-full-build.log`,
завершение 03:53:20 Bangkok. Все пять новых нативных сценариев прошли с
полным равенством RPC-ответов и неизменностью пулов при отказах.
Исходная IDEA GPU-нода PID 75688 перезапущена: RUNNING,
blocks=headers=974984, IBD=false, семь соединений (два block relay),
24 825 записей mempool, один Bitcoin-процесс и ноль привилегированных пиров.
В журнале нового PID подтверждены принятые CUDA shares.
Снимок: `target/core-update-audit/core32-shared-submission-live-verification.json`.
