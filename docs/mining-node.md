# Запуск майнингового контура

Нода поддерживает начальную и последующую синхронизацию, получение транзакций,
создание шаблона через JSON-RPC и распространение локально найденного блока.
Внешний сервер майнинга собирает coinbase, распределяет работу ASIC-майнерам
и отправляет найденный блок через `submitblock`. Также реализован собственный
[Stratum V1 для solo mining](stratum.md), работающий независимо от RPC.

## Конфигурация

RPC выключен по умолчанию. Пример локальной regtest-конфигурации:

```yaml
bitcoin:
  network: regtest
  data-directory: ./data/regtest
  node:
    auto-start: true
  p2p:
    peers: 127.0.0.1:18444
    target-outbound-peers: 1
    header-response-timeout-millis: 10000
  rpc:
    enabled: true
    bind: 127.0.0.1
    port: 18443
    user: bitcoin
    password: ${BITCOIN_RPC_PASSWORD}
  mining:
    # Только regtest: OP_TRUE. Для реальной сети задайте свой payout script.
    payout-script: "51"
    maximum-weight: 3996000
    minimum-fee-sat-per-kvb: 1000
```

Порт RPC Java-ноды должен отличаться от RPC-порта соседнего Bitcoin Core.
При включённом RPC обязательны пароль и непустой `payout-script`.
HTTP разрешён только на loopback; для доступа с другой машины нужен защищённый туннель.
Basic authentication использует `bitcoin.rpc.user` и `bitcoin.rpc.password`.

`payout-script` используется при внутренней сборке кандидата и резервировании веса.
RPC возвращает `coinbasevalue`: фактические выходы coinbase и адрес получения награды
задаёт внешний сервер майнинга. Он обязан заново проверить итоговые weight/sigops,
если меняет coinbase или набор транзакций.

## RPC

POST `/`, совместимый с базовым форматом Bitcoin JSON-RPC:

```json
{"id":1,"method":"getblocktemplate","params":[{"rules":["segwit"]}]}
```

Возвращаются заголовочные поля, высота, `coinbasevalue`, транзакции с зависимостями,
комиссиями и sigops, лимиты и `default_witness_commitment`.
Шаблон строится под общей блокировкой цепочки и mempool, с контекстной проверкой.
Для Merkle root используйте `txid`, для witness commitment — `hash`/wtxid.
Внешний сборщик должен добавить BIP34-высоту, extranonce и witness reserved value
в coinbase и согласовать witness commitment с итоговым набором транзакций.

Следующий запрос может включать полученный `longpollid`:

```json
{"id":2,"method":"getblocktemplate","params":[{"rules":["segwit"],"longpollid":"значение предыдущего ответа"}]}
```

Запрос ждёт изменения цепочки/mempool или истечения 30 секунд. Одновременно разрешены
четыре long-poll запроса; остальные RPC-потоки доступны для отправки решений.
При `submitold: false` майнер должен отменить старое задание. Это уведомление,
а не удалённая остановка вычислений ASIC.

```json
{"id":3,"method":"submitblock","params":["полный сериализованный блок в hex"]}
```

Успешное подключение возвращает `result: null`, повтор активного блока — `duplicate`.
Неизвестный родитель — `prev-blk-not-found`, сохранённая боковая ветка с отложенной
контекстной проверкой — `inconclusive`. Последняя не объявляется пирам как принятый блок.
Решение проходит обычный `NodeValidationService.processBlock`, затем объявляется пирам;
по `getdata` отдаётся тело с нужным вариантом witness-сериализации.

Дополнительно доступны `sendrawtransaction`, `getrawmempool`, `getblockchaininfo`
и `getmininginfo`. Реализован базовый формат с позиционными параметрами; JSON-RPC batches,
GBT proposal mode и все опциональные параметры Bitcoin Core не поддержаны.
Это не полная замена RPC Bitcoin Core.

Выдача шаблонов запрещена до завершения IBD, без READY-пиров, при отставании от
лучшего известного заголовка или отсутствии свежей успешной проверки синхронизации.
Для сетей кроме regtest дополнительно проверяется возраст active tip (до двух часов).
Signet-майнинг отклоняется: подпись challenge не реализована.

## Сетевой цикл

- `LiveChainSynchronizer` отслеживает block inv и unsolicited headers; заголовки
  и тела проверяются на lifecycle-потоке, вне peer reader.
- Подписки на новые соединения восстанавливаются; раз в 30 секунд выполняется
  контрольный опрос, покрывающий пропущенное объявление при handshake/reconnect.
- `NodeRelayService` обслуживает tx inv/getdata/tx и getheaders/getdata.
  Поддержаны txid и BIP339 wtxid; mempool-политики остаются общими с локальным приёмом.
- Очередь ограничена 128 сообщениями и 16 MB payload; запросы транзакций — 1024
  outstanding, до 128 в одном сообщении. Orphan cache — до 100 транзакций по 100 KB,
  срок хранения две минуты; недостающие родители запрашиваются и дети перепроверяются.
- Отдача блоков ограничена активной цепочкой: наличие сохранённой боковой ветки
  само по себе не подтверждает её контекстную валидность.

## Проверка

```powershell
.\mvnw.cmd -o test -q
.\mvnw.cmd -o -pl app -am test '-Dtest=BitcoinCoreMiningRoundTripTest' '-Dsurefire.failIfNoSpecifiedTests=false' '-Dbitcoin.core.binary=C:/Program Files/Bitcoin/daemon/bitcoind.exe'
```

Второй тест запускает отдельный Core с временным datadir и свободными локальными
портами, создаёт regtest-кошелёк и 101 блок, проверяет транзакционный relay, майнинг
шаблона, приём блока Core и продолжение после reconnect. Core останавливается в finally.
Временный Core получает `noban` для loopback, чтобы случайная задержка tx announcements
не делала этот тест нестабильным; производственная конфигурация пиров не меняется.

`MiningRpcTest` отдельно проверяет настоящий HTTP endpoint, authentication, запрет
майнинга до синхронизации, long polling и сборку блока из ответа RPC.
`NodeLifecycleServiceTest` покрывает новый блок и смену ветки после RUNNING.

## Что ещё требуется перед эксплуатацией в mainnet

Полный исторический mainnet-прогон, широкое дифференциальное сравнение с Core,
аварийное восстановление после отключения питания, длительные нагрузочные испытания
и работа с реальным mining proxy/ASIC не подтверждены этими тестами.

Входящий P2P listener и addr/addrv2/getaddr уже реализованы и подключены к lifecycle.
Остаются: проверка Stratum с реальным ASIC, compact-block relay,
усиление адресного обмена и защиты от eclipse/DoS, постоянный учёт невалидных веток,
pruning/reindex, эксплуатационные метрики и оптимизация обходов цепочки/mempool.
Сейчас обслуживание старого block locator требует прохода по предкам через RocksDB;
это нуждается в оптимизации перед открытием сервиса произвольным пирам.
Входящая синхронизация сама по себе не обеспечивает relay каждого полученного блока
всем остальным пирам; гарантирован путь публикации локально найденного блока.
Конкретные находки и приоритеты: [аудит готовности](readiness-audit.md).

Источники: [BIP22](https://github.com/bitcoin/bips/blob/master/bip-0022.mediawiki),
[BIP145](https://github.com/bitcoin/bips/blob/master/bip-0145.mediawiki),
[BIP339](https://github.com/bitcoin/bips/blob/master/bip-0339.mediawiki).
