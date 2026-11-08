# Первый locator: начать перед лучшим заголовком

Эталон: Core 32.1 `net_processing.cpp`, начальная ветка SendMessages:
[исходник Core](https://github.com/bitcoin/bitcoin/blob/v32.1/src/net_processing.cpp).
При наличии родителя первый GETHEADERS строится от родителя лучшего заголовка.
Это даёт непустое подтверждение уже известной вершины от актуального пира,
позволяя установить известную работу его цепи. Запрос от самой вершины обычно
давал бы пустой ответ, который не содержит такого подтверждения.

В BlockLocatorBuilder добавлено построение от предшественника с обычным
GetLocator-алгоритмом; для genesis остаётся genesis. Последовательность шагов
сохранена относительно [Core LocatorEntries](https://github.com/bitcoin/bitcoin/blob/v32.1/src/chain.cpp).
Простое удаление первого хеша из старого locator не используется: после
перехода к экспоненциальным шагам результат был бы другим.

Рабочий CoreHeadersResponseControl подменяет locator только при начале
синхронизации данного пира, включая повторное начало после сброса. Вершина
для расчёта срока и построения locator берётся одним неизменяемым снимком.
Продолжение загрузки сохраняет locator вызывающей стороны. Отказ transport
gate откатывает начало; последующая попытка снова получает начальный locator.
Версия протокола и stop hash сохраняются. Явные пользовательские тайм-ауты
с прежним режимом не переключаются на эту политику.

Два новых случая CoreHeaderTimingIntegrationTest проверяют genesis и высоту
30: полный вектор высот начального locator, откат gate, успешную повторную
попытку, неизменённый locator продолжения и единственный счётчик начала.
Для высоты 30 ожидается
`29,28,27,26,25,24,23,22,21,20,19,18,16,12,4,0`.
Выборочные coordinator/lifecycle/locator проверки прошли:
`target/core-update-audit/core32-initial-locator-focused.log`.

Полный scheduler и окно VERSION/VERACK остаются отдельной работой;
[границы соответствия](header-sync-timing-core32.md).

Полный Bitcoin/RVN reactor: 5355 тестов, 5343 успешных, 12 пропущенных,
без ошибок; оба приложения упакованы. Журнал
`target/core-update-audit/core32-initial-locator-full-build.log`,
8 ноября 2026, 15:38:18 Bangkok. После перезапуска исходная IDEA GPU-нода
PID 127368: RUNNING, height=headers=975042, IBD=false, семь пиров (два block
relay), 35204 записи mempool, ноль привилегированных пиров, принятые CUDA shares
и ровно один Bitcoin-процесс. Проверка:
`target/core-update-audit/core32-initial-locator-live.json`.
