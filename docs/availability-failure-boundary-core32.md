# Availability-пробы: внутренние ошибки и транспорт

В refresh CoreBlockDownloadPeerPolicy построение locator и отправка запроса
находились в одном catch IOException/IllegalStateException. Поэтому отсутствие
предка в локальном индексе или иная внутренняя IllegalStateException могли
молча трактоваться как проблема пира. В Core GetLocator работает с доверенным
деревом индексов; повреждение этого дерева не является сетевым отказом пира.

Построение locator вынесено за транспортный catch. Внутренняя ошибка при
открытом соединении поднимается вызывающей стороне. Гонка уже закрытого пира
остаётся локальным событием; IOException не останавливает refresh и не
добавляет новый cooldown неотправленной пробе. Пир не получает protocol violation
за локальную ошибку хранилища.

Дополнительно устранена неоднозначность закрытия транспорта: сокет может уже
закрыться до публикации PeerState.CLOSED читателем. Async submission в закрытый
PeerConnection возвращает IOException. На уровне GETHEADERS как sync, так и
async путь переводят IllegalStateException закрывшегося транспорта в IOException,
сохраняя причину. При открытом транспорте исходная IllegalStateException
сохраняется. Gate освобождается в обоих случаях, позволяя повторить отправку.

Проверки:

- Настоящий BlockLocatorBuilder с отсутствующим предком: ошибка поднимается,
  запрос не отправляется, пир не наказывается.
- Ошибка отправки при открытом/закрытом пире и немедленный повтор после IO.
- Четыре комбинации sync/async и connected/closed с детерминированно
  воспроизведённым промежутком до reader cleanup; проверен повторный допуск.
- Закрытый управляемый тестовый Socket не превращается во внутреннюю ошибку
  async submission.
- Существующие NodeStorageFailureTest, InitialHeaderRejectionTest и lifecycle
  сценарии также прошли выборочный reactor:
  `target/core-update-audit/core32-availability-failure-boundary-focused-complete.log`.

Это уточнение границы ошибок в availability-пути и отправке GETHEADERS, а не
завершение полной классификации ошибок блоков/транзакций;
[остальной объём](header-sync-timing-core32.md).

Полный Bitcoin/RVN reactor завершён 8 ноября 2026, 21:13:45 Bangkok:
5373 случая, 5361 успешный, 12 пропущенных, без failures/errors; обе программы
упакованы. Журнал: `target/core-update-audit/core32-availability-failure-boundary-full-build.log`.
Выборочная проверка: 89 случаев, все успешны.

После сборки исходная IDEA GPU-нода PID 44408 проверена на высоте 975071,
IBD=false. При продолжении 11 ноября в 21:46 Bangkok текущий единственный
Bitcoin-процесс имеет PID 51816: blocks=headers=975447, IBD=false,
11 подключений (3 block relay), 37799 транзакций mempool, 0 привилегированных
пиров; в журнале CUDA accepted=79. Это отдельная текущая эксплуатационная
проверка, не повторный запуск тестов. Снимок:
`target/core-update-audit/core32-availability-failure-boundary-live-current.json`.
