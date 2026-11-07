# Отрицательная modified fee при приёме

Эталон: [Core 32.1 CheckFeeRate](https://github.com/bitcoin/bitcoin/blob/v32.1/src/validation.cpp#L643).
Modified fee включает локальную fee delta и может быть отрицательной при
неотрицательной base fee. Это причина отказа политики комиссии, а не ошибка
аргумента RPC/API.

MempoolPolicy.validateFee допускает signed modified fee как вход и возвращает
MempoolAdmissionException при недостаточной комиссии. Неверный weight остаётся
IllegalArgumentException. Одиночная и пакетная пробы возвращают allowed=false,
вместо утечки IllegalArgumentException; поправка и рабочий пул сохраняются.
Submission с CPFP сохраняет возможность оплатить отрицательную modified fee
родителя комиссией ребёнка.

NegativeModifiedFeeAdmissionTest проверяет нулевую/положительную ставку minrelay,
неизменность одиночной пробы и приёма, сохранение delta, отказ пакетной пробы
и успешный CPFP с base fee 1000, delta -1001 и modified fee -1 у родителя.
Нативный BitcoinCore32CompatibilityTest применяет -1001 к подписанному
родителю с base fee 1000 в Core и Java, проверяет fee-policy отказ RPC,
затем отменяет delta и проверяет неизменность обоих mempool.

Приоритет отказа и класс ошибки проверяются, но тексты minrelay пока
различаются. Приоритет rolling minimum относительно minrelay, полная
signed-fee арифметика и все метаданные после отрицательной prioritisation
остаются отдельными направлениями проверки.

Проверка 7 ноября 2026: три новых сценария и нативный Core 32.1 тест прошли.
Полная Bitcoin/RVN-сборка завершена в 17:51:50 Bangkok:
5 304 случая, 5 292 пройдено, 12 opt-in пропущено, ошибок нет;
оба приложения собраны. Логи:
`target/core-update-audit/core32-negative-modified-fee-focused.log`,
`target/core-update-audit/core32-negative-modified-fee-full-build.log`.

Исходная IDEA-конфигурация BitcoinNodeApplication GPU перезапущена из
app/target/classes с mainnet/gpu, PID 73396. Проверено RUNNING,
height=headers=974925, IBD=false, девять подключений (два block relay),
22 506 записей mempool, один Bitcoin-процесс, ноль привилегированных пиров
и принятые CUDA shares. RPC-снимок:
`target/core-update-audit/core32-negative-modified-fee-live-verification.json`.
