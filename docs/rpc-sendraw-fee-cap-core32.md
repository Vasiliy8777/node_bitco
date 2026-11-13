# sendrawtransaction: лимит комиссии и повтор известного txid

Эталон Core 32.1: `core32-rpc-mempool-reference.cpp`,
`core32-node-transaction-reference.cpp` (BroadcastTransaction),
`core32-feerate-reference.cpp` (CFeeRate::GetFee).

Исправления:

- Клиентский лимит сравнивается с base fee, а не modified fee после
  prioritisetransaction. Fee delta не увеличивает и не уменьшает реальную
  сумму, которую пользователь заплатит за транзакцию.
- Максимальная допустимая сумма рассчитывается как
  `ceil(maxfeerate_sat_per_kvB * BIP141_vsize / 1000)`.
  Прежнее перекрёстное сравнение могло отказать на дробной границе,
  допускаемой округлением Core, и зависело от произведения modified fee.
- Проверка cap выполняется для новой транзакции. Известный mempool txid
  допускается к повторному объявлению даже с tiny cap и иным witness.
- Relay использует уже принятую транзакцию с сохранённым witness;
  повтор не запускает admission, регистрацию нового unbroadcast или обработку
  потомков заново. Первичный приём сохраняет прежние действия.

Burn и синтаксис fee по-прежнему проверяются до решения о повторном объявлении,
как в Core. maxfeerate=0 отключает дополнительную предварительную пробу cap.

Нативная матрица расширена:

- Повтор исходного и изменённого witness известного txid при tiny cap.
- Независимый подписанный parent с base fee 10000 sat: соседние ставки
  вокруг минимального допускаемого округлённого cap.
- С отрицательной fee delta недостаточный cap отклоняет parent без приёма.
- С положительной fee delta ровно достаточный cap принимает parent.
- Состав обоих mempool проверяется существующей пакетной матрицей.

Это проверка указанных сценариев, не завершение всех sendrawtransaction ошибок.
Подтверждённые/потраченные транзакции, полная типизация admission-ошибок,
полная матрица reorg/eviction, private broadcast и полная
wire-матрица повторных объявлений остаются отдельной работой.
Окно между отдельными пробой и приёмом закрыто
[общей операцией под chain monitor](atomic-broadcast-core32.md).
[Остальной объём](core32-user-checklist.md).

Выборочный reactor: 39 случаев, 0 failures/errors/skips, включая полный
существующий BitcoinCore32CompatibilityTest и выбранные relay/package/RPC
проверки. Завершён 12 ноября 2026, 01:37:17 Bangkok;
`target/core-update-audit/core32-sendraw-feecap-focused.log`.
Нативный тест прошёл за 92.88 секунды. Это целевая проверка изменения,
а не повтор полного reactor 5378 случаев.

Bitcoin/RVN упакованы с `-DskipTests` в 01:37:58 Bangkok;
`target/core-update-audit/core32-sendraw-feecap-package.log`.
После сборки запущена одна исходная IDEA GPU-нода PID 84280: RUNNING,
blocks=headers=975464, IBD=false, 9 пиров (2 block relay), 41280 mempool entries,
0 привилегированных пиров, принятые CUDA shares. Снимок 01:38:47:
`target/core-update-audit/core32-sendraw-feecap-live.json`.
