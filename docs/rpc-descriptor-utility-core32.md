# Descriptors, util и decodescript — Core 32.1

Реализованы все три метода категории util: `getdescriptorinfo`,
`deriveaddresses`, `validateaddress`. Также добавлен `decodescript`.
Они используют один движок descriptors, без отдельного процесса ноды.

`OutputDescriptor` и `DescriptorKey` разбирают pk/pkh/wpkh, sh/wsh,
multi/sortedmulti, multi_a/sortedmulti_a, combo, tr с деревьями,
rawtr, addr, raw, unused и Miniscript. Поддержаны origins, WIF,
xpub/xprv, hardened/ranged/multipath пути и MuSig2/BIP328. Секрет одного
провайдера может использоваться для деривации соответствующего xpub
другого провайдера, как в общем FlatSigningProvider Core.

`Bip32Key` проверяет формат, сеть, root metadata, ключи, глубину и индексы;
неудачная деривация не подменяется другим индексом. Официальные BIP32
векторы проверяют публичную/приватную деривацию, leading zeros,
neutering, сериализацию и некорректные extended keys.

Miniscript разбирается и кодируется итеративно: текст, Script,
VERIFY folding и syntactic sugar. Отдельно рассчитываются типы,
маллеабельность, совместимость timelock, повторяющиеся ключи и
максимальные ops/стек по каноническим удовлетворениям. При разборе
сохранены ранний бюджет размера и порядок вызова парсера ключей:
это влияет на точные сообщения о повреждённых expressions.

`getdescriptorinfo` возвращает канонический публичный descriptor,
multipath_expansion, checksum исходного выражения и признаки
isrange/issolvable/hasprivatekeys. `deriveaddresses` использует общий
разбор/развёртывание, точную проверку диапазона и возвращает отдельные
массивы для multipath. Для combo пропускается P2PK-выход, не имеющий адреса.

`decodescript` соблюдает проверки HasValidOps, OP_SUCCESS/CHECKSIGADD,
размеров push и unspendable, условия P2SH/P2WSH, uncompressed keys,
обёртки существующих witness programs и вывод descriptor с известным
witness script. Несостоятельное Miniscript-выражение при inference
не подменяется выдуманным wsh(raw(...)).

`validateaddress` воспроизводит выбор Base58/Bech32 по HRP сети,
правила whitespace, checksum/длины/padding/version и DescribeAddress,
включая отсутствие witness_version/program у anchor и отсутствие
isscript у неизвестных witness versions. Смещения ошибок — байты UTF-8.
Checksum locator использует линейность BCH checksum: таблица вкладов
одного символа и поиск пары, без рекомендации исправленного адреса.

Нативные проверки Bitcoin Core 32.1:

- 515 ключевых выражений: канонизация, ошибки и адреса, включая общий
  приватный root для hardened participant внутри MuSig2.
- 8104 descriptor-сценария из C++ тестов Core и мутаций; 601 развёртывание
  адресов; повреждённые checksum, синтаксис, диапазоны и подробные ошибки.
- 3200 сценариев Miniscript: Script→текст→Script и исходный текст→Script,
  адрес после повторного разбора текста самим Core.
- 38 явных границ sanity/ops, повторяющихся ключей и timelock.
- 1726 decodescript: скрипты descriptors, все byte-opcodes, push boundaries,
  повреждения и фиксированная случайная выборка.
- По 7848 validateaddress-сценариев на mainnet/testnet/regtest, всего 23544:
  Core key_io vectors, одно/двухсимвольные ошибки, mixed case, UTF-8,
  padding, версии, размеры и Base58 boundaries.
- По 681 сквозному util/decodescript HTTP-сценарию на трёх сетях.
- Текущая общая матрица RPC/schema/help/type/arity: 647 сравнений.

Логи находятся в `target/core-update-audit/`:
`core32-descriptor-keys-native.log`, `core32-output-descriptors-edge-native.log`,
`core32-miniscript-limits-native.log`, `core32-decodescript-native.log`,
`core32-validateaddress-native.log`, `core32-descriptor-utility-http-native.log`.

Полная RPC-совместимость не заявляется: updater/signing/funding, wallet,
остальные node RPC и общая транспортная матрица остаются открыты.
Каталог исходников: 74 из 176 имён, отсутствуют 102. Реализация
`descriptorprocesspsbt` не объявляется выполненной наличием парсера.

Полная сборка `core32-rpc-descriptor-bitcoin-build.log`: BUILD SUCCESS,
15 643 теста, 12 пропусков, 0 ошибок/отказов, 481 набор.
Исходная конфигурация IDEA `BitcoinNodeApplication GPU` перезапущена:
PID 95088, mainnet/gpu, высота blocks=headers=975750, IBD=false.
Живая проверка всех пяти новых RPC успешна; результаты:
`core32-rpc-descriptor-live.json`, `core32-rpc-descriptor-live-smoke.json`.
