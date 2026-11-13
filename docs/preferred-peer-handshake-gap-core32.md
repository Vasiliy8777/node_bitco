# Предпочтительные пиры в промежутке VERSION → VERACK

Проверка исходников и исправление 11 ноября 2026. Ниже описана причина
различия, устранённого отдельным учётом незавершённых соединений.

В сохранённом эталоне `target/core-update-audit/core32-net-processing.cpp`
обработчик VERSION после отправки VERACK задаёт `fPreferredDownload` и
увеличивает `m_num_preferred_download_peers` (около строки 4010). Получения
ответного VERACK для этого не требуется. Предпочтение зависит от направления,
NoBan, роли ADDR_FETCH и способности обслуживать блоки.

До исправления Java публиковала `remoteVersion` внутри `Peer.handleVersion`, но
`PeerManager.preferred` использует `canServeBlocks`, требующий `isReady()`.
Дополнительно управляемое соединение ещё отсутствует в менеджере:

- `OutboundPeerManager.connectReservedPersistentCandidate` регистрирует пир после возврата
  `connectManaged`, проверки READY и пригодности для постоянного подключения.
- `BitcoinServer` выполняет `peer.handshake(false)` до `peerManager.add`.
- `BitcoinClient` завершает handshake внутри connect.

Поэтому убрать проверку READY недостаточно: менеджер всё равно не видит
незавершённые соединения. Регистрировать их как обычные готовые пиры также
неправильно: `add` уведомляет наблюдателей и запускает managed reader, тогда
как чтением во время handshake ещё владеет handshake-путь.

Влияние: начавший синхронизацию источник может быть сохранён после срока,
пока альтернативный исходящий/NoBan пир уже прошёл VERSION, но ещё не VERACK.
При отсутствии блоков в полёте fallback может скрыть разницу в выборе
источника; проверять нужно и состояние с блоками в полёте.

Теперь `PeerManager.trackHandshake` хранит отдельные identity-записи ролей.
Счётчик читает опубликованный VERSION и учитывает подходящие записи ещё до
READY. Регистрация не запускает reader и не вызывает наблюдателей готовых пиров.
Обычный `add` атомарно переносит запись в основной набор; close-listener удаляет
неуспешную запись. Закрытый пир исключается даже до вызова listener. `close`
менеджера закрывает и незавершённые соединения.

BitcoinClient подключён к этому учёту в production-конфигурации; независимый
клиент manual peers наследует настройку, включая роль MANUAL. При v2→v1 fallback
закрытие удаляет старую запись, новый Peer регистрируется отдельно. BitcoinServer
регистрирует inbound перед handshake. Незавершённые пиры не входят в readyPeers,
не получают резервирование headers sync и задания блоков.

Регрессионная матрица:

- Локальный socket-пир отправляет VERSION, задерживает VERACK; счётчик уже
  учитывает его, но readyPeers и допуск отправки GETHEADERS — ещё нет.
- Тот же случай для обычного inbound, inbound NoBan, ADDR_FETCH, клиента без
  NETWORK/LIMITED; учитываются только подходящие комбинации.
- Закрытие/ошибка/отмена до VERACK убирают запись; успешный VERACK не удваивает её.
- При истёкшем сроке единственного sync-пира и такой альтернативе обычный
  источник отключается, NoBan сбрасывается; точная граница срока сохраняется.
- Нативный Core wire-сценарий должен задерживать именно VERACK альтернативы,
  а не подменять этот случай уже готовым соединением.

BitcoinClientTest использует настоящий сокет с задержанным VERACK и проверяет
счётчик до READY и после переноса. PreferredHeadersPeerTest проверяет роли,
сервисы, NoBan, закрытие и решение timeout при такой альтернативе.
BitcoinCoreHeadersTimingWireTest дополнен отдельной VERSION-only альтернативой
для обоих вариантов NoBan, сохраняя прежние случаи полной готовности.
Это не закрывает весь scheduler и import/reindex.
[Общий статус синхронизации](header-sync-timing-core32.md).

Выборочный reactor: 46 случаев, без ошибок и пропусков,
`target/core-update-audit/core32-handshake-preference-focused-final.log`.
Независимый Core wire reactor: 5 случаев, без ошибок и пропусков,
`target/core-update-audit/core32-handshake-preference-native-final.log`.
В первоначальном native-прогоне фикстура неверно ожидала EOF сразу после VERACK:
Core вправе прислать GETADDR до завершения handshake. Она теперь читает полные
сообщения до EOF, сохраняя отсутствие своего VERACK. Оба новых сценария прошли
после этой корректировки; production-код ради ошибки фикстуры не изменялся.

Полная сборка Bitcoin/RVN: 5378 случаев, 5366 успешных, 12 пропущенных,
0 failures/errors; обе программы упакованы 11 ноября, 22:09:58 Bangkok.
Журнал: `target/core-update-audit/core32-handshake-preference-full-build.log`.
После сборки запущена исходная IDEA-конфигурация GPU, один Bitcoin-процесс
PID 78900. Проверка 22:10:51: RUNNING, blocks=headers=975447, IBD=false,
5 пиров (2 block relay), 39667 mempool entries, 0 привилегированных пиров;
журнал после запуска подтверждает принятые CUDA shares.
Снимок: `target/core-update-audit/core32-handshake-preference-live.json`.
