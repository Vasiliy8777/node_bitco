# Broadcast preflight: первый отказ без повторного admission

Эталон Core 32.1: BroadcastTransaction в
`target/core-update-audit/core32-node-transaction-reference.cpp`.
При включённом client fee cap Core сначала выполняет test_accept; неуспешный
результат немедленно возвращается через HandleATMPError. Повторный обычный ATMP
выполняется только после успешной пробы и проверки комиссии.

Java ранее получала TestAcceptResult, но при allowed=false продолжала обычный
admission. Это повторяло отклонённую валидацию, теряло исходный тип отказа и
могло скрыть изменившийся или внутренний отказ вторым результатом.

Добавлен Mempool.testBroadcast: обычный admission на изолированной копии
entries/fee deltas/rolling fee/spent index. Он возвращает MempoolEntry либо
выбрасывает исходное исключение. Настоящий pool и unbroadcast не изменяются.
Создание клона вынесено в общий helper; прежние testmempoolaccept и
submitpackage сохраняют свои diagnostic flags и преобразование результатов.

NodeValidationService использует эту типизированную пробу в общей chain
критической секции. При отказе не вызываются admit и markMempoolUnbroadcast.
При maxfeerate=0 дополнительная проба по-прежнему пропускается; известный
txid возвращается до пробы. Сетевое объявление остаётся после успешной операции.

Добавлены три случая NodeValidationServiceTest: неизменность пула после
настоящей успешной пробы, немедленное сохранение policy-исключения и
немедленное сохранение внутренней IllegalStateException. Spy проверяет
отсутствие окончательного admission/unbroadcast при отказе. Существующая
детерминированная проверка chain monitor переведена на новую пробу.

Это не завершает диагностику всех legacy/Taproot script errors и соответствие
всех внутренних ошибок Core MEMPOOL_ERROR. Полный список:
[core32-user-checklist.md](core32-user-checklist.md).

Выборочная проверка: 55 случаев, 0 failures/errors/skips,
12 ноября 2026, 06:17:30 Bangkok;
`target/core-update-audit/core32-broadcast-preflight-focused.log`.
Включены NodeValidationServiceTest, BitcoinCore32CompatibilityTest,
MiningRpcTest, NodeRelayServiceTest, OrdinaryRelayFeeFilterTest и
PartialPackageSubmissionTest. Это не новый полный reactor 5378 случаев.

Bitcoin/RVN упакованы с `-DskipTests` после проверок, 06:18:13 Bangkok;
`target/core-update-audit/core32-broadcast-preflight-package.log`.
Одна исходная IDEA GPU-нода PID 94528: RUNNING, blocks=headers=975489,
IBD=false, 7 пиров (2 block relay), 32457 mempool entries, 0 привилегированных
пиров, принятые CUDA shares. RPC-снимок 06:19:10:
`target/core-update-audit/core32-broadcast-preflight-live.json`.
