# Реализация и запуск CUDA-майнинга — 2026-10-07

Последующие исправления и проверки Core, аварийного восстановления, ADDRv2
и GPU/Stratum описаны в [pre-mainnet-validation-20261008.md](pre-mainnet-validation-20261008.md).

## Обновление: запуск из IntelliJ

По запросу пользователя mainnet-нода и отдельный CUDA-воркер остановлены.
Сейчас основной процесс не запущен. Для следующего старта добавлена общая
Spring Boot конфигурация **BitcoinNodeApplication GPU** (`.run`, модуль `app`,
профили `mainnet,gpu`, working directory — корень проекта).
Локальная конфигурация `BitcoinNodeApplication` также переведена на эти профили;
старый параметр, подменявший payout адрес тестовой переменной, удалён.

Теперь Spring Boot сам запускает `GpuMinerProcess` после ApplicationReadyEvent
и завершает его при закрытии контекста. Вывод CUDA поступает в консоль Run.
Пароль из `BITCOIN_STRATUM_PASSWORD`, либо случайный UUID, разрешается один раз
и передаётся одинаковым Stratum серверу и subprocess; на диск не записывается.
PowerShell launcher отключает управляемый Spring воркер, чтобы не запускать два.

Проверка: **14 тестов без failures/errors**, включая реальный запуск RTX 4070
из Spring Boot в изолированном regtest-контексте, успешную авторизацию со
случайным паролем и завершение CUDA процесса при закрытии контекста.
Лог: `target/gpu-mining/intellij-launch-tests.log`.
Ниже сохранены результаты предыдущего mainnet-запуска.

Добавлен отдельный Python/CUDA клиент `tools/gpu-miner`. Он подключается к
Stratum V1 ноды, собирает coinbase и Merkle root, перебирает nonce на GPU,
сравнивает полный 256-битный target, перепроверяет решения через CPU SHA-256d
и отправляет их серверу. CUDA kernel использует midstate. После 2^32 nonce
клиент меняет extranonce2; диапазоны не переполняются. Новые шаблоны отменяют
старую работу, изменение только difficulty не запускает повторный поиск уже
пройденных nonce. Время заголовка берётся из задания сервера.

NVRTC компилирует kernel во время запуска; CUDA Driver API исполняет PTX.
Это устраняет необходимость host compiler MSVC для данного клиента.
Установленная связка CUDA 12.2 / RTX 4070 / драйвер 610.88 работает без замены
Visual Studio и без дополнительных Python-пакетов.
См. [официальное описание NVRTC](https://docs.nvidia.com/cuda/archive/12.2.0/nvrtc/index.html).

## Проверка

- GPU self-test: 513 проверок хешей, включая mainnet genesis, случайные
  заголовки, nonce вплоть до `ffffffff`, включительное сравнение полного
  target, переполнение буфера решений и отказ от неверных диапазонов; успешно.
- CUDA benchmark 5 секунд, пакет 262144: **1754.85 MH/s**. Это скорость локального
  поиска SHA-256d, а не измерение заработка или шанс нахождения блока.
- 4 Python protocol tests: target без Decimal-округления, compact target,
  порядок байтов заголовка и Merkle root, применение difficulty к следующему
  заданию и отмена старой работы; успешно.
- Последний Maven запуск: **27 тестов, 0 failures/errors, 1 skipped** (opt-in
  IBD benchmark). Mining RPC, шаблоны, payout, Stratum, version bits,
  testnet4 и два реально исполненных теста Bitcoin Core.
- В Core round-trip нода синхронизируется с изолированным Core regtest через
  P2P. Настоящий CUDA subprocess ищет блок, Java Stratum его проверяет,
  подключает и публикует. Core достигает высоты **106**, его tip совпадает
  с Java tip, payout script в принятой coinbase совпадает с адресом кошелька,
  выданным Core. В журнале: `GPU_CORE_ROUND_TRIP ... Accepted block`.

Лог Java проверки: `target/gpu-mining/verification.log`; XML результатов в
`app/target/surefire-reports`. Логи и бинарники под `target` не коммитятся.

## Конфигурация и запуск

Сохранён `payout-address: ${BITCOIN_MINING_PAYOUT_ADDRESS:}`. Адрес не копируется
в исходники или отчёт. Launcher выбирает mainnet по `bc1`, явно включает
профили `mainnet,gpu` и сеть mainnet, поэтому прежний default testnet не вызывает
ошибку HRP. Адрес и checksum проверяет Java decoder. Stratum на публичных сетях
отклоняет fallback OP_TRUE (`51`); обычный RPC режим синхронизации сохраняет
совместимость с предыдущей настройкой.

Профиль `gpu` включает localhost Stratum:3333, difficulty 1, vardiff minimum
0.001 / target 15 секунд / retarget 60 секунд. Launcher использует
`BITCOIN_STRATUM_PASSWORD`, либо генерирует общий случайный пароль для дочерних
процессов без записи пароля на диск. Spring Boot jar теперь создаётся при
`package`. Остановка Java выполняется через локальный JMX Spring shutdown;
проверены завершение процесса и сохранение заголовков после повторного старта.

```powershell
./tools/gpu-miner/Start-GpuMining.ps1
./tools/gpu-miner/Get-MiningStatus.ps1
./tools/gpu-miner/Stop-GpuMining.ps1
```

Руководство клиента: [tools/gpu-miner/README.md](../tools/gpu-miner/README.md).

## Текущее состояние и граница проверки

В 20:38:45 Asia/Bangkok mainnet-нода повторно запущена с PID **32880**, CUDA
воркер с PID **29032**. Состояние записано в `target/gpu-mining/session.json`,
журналы — `target/gpu-mining/20261007-203845`. В 20:43:23 загружены все
**970347 заголовков** и первые **954 блока**, RPC сообщает 13 соединений:
`initialblockdownload=true`, `miningready=false`. Воркер авторизован и выводит
`Waiting for synchronized mining work; GPU idle`. Данные основной сети пишутся
в отдельную базу под настроенным `K:/BitcoinJavaNode`; существующие testnet
базы не заменялись. На K: перед запуском доступно около 2965 GiB.

**Mainnet hashing ещё не начался:** сначала нода должна завершить первоначальную
синхронизацию и получить актуальный peer view. После готовности сервер выдаст
задание подключённому воркеру автоматически. При потере готовности задания
аннулируются, соединение закрывается, воркер переподключается и ждёт.

Подтверждён путь GPU → Stratum → локальный консенсус → P2P → Core на regtest.
Это не является доказательством полной совместимости консенсуса mainnet или
длительной устойчивости под нагрузкой. Mainnet IBD продолжается; проверки
реального ASIC возможны после подключения устройства. Для ASIC нужен профиль
с подходящей начальной difficulty и доступом к Stratum из локальной сети;
рекомендации приведены в README. Одновременные GPU/ASIC с сильно различным
хешрейтом потребуют отдельных стартовых сложностей/endpoint.
