# Tasks

## 1. Старт и конец трека

- [x] 1.1 В `ListeningTracker.onTick` создавать трек только при `PLAYING`; покрыть тестом «служба стартовала на паузе — start появляется только после resume»
- [x] 1.2 Добавить `endReason = not_played` для `playedMs < 3_000` без `completed`; проверить тестом и исключить из оценённых окончаний
- [x] 1.3 Обновить `docs/METRICS.md` и `docs/ARCHITECTURE.md`; проверить, что описание `not_played` совпадает с тестом
- [x] 1.4 Распознавать repeat-one: позиция у конца → 0 при `PLAYING`; закрывать как `completed` и начинать новый play; покрыть тестом

## 2. Штатный плеер

- [x] 2.1 Добавить `playingKnown` в `StockPlayerState` и использовать `isMusicActive`, если `play_state` отсутствует; покрыть тестами broadcast без `play_state`
- [x] 2.2 Для `NOTIFICATION` определять игру по `isMusicActive`, а не по факту уведомления; покрыть тестом «уведомление есть, музыка не активна»
- [x] 2.3 Увеличить таймауты: пауза 10 минут, broadcast/playing 30 минут, accessibility 10 минут; проверить тестом паузы 30 секунд без второго `track_started`
- [x] 2.4 Обновить `docs/ARCHITECTURE.md`; проверить, что таймауты и `isMusicActive` описаны

## 3. Mute и громкость

- [x] 3.1 `VolumeSelector` возвращает сырое значение и `muted`; обновить `VolumeSelectorTest`
- [x] 3.2 В `ListeningTracker` писать `mute_changed` и не писать ложный `volume_changed`; покрыть тестами mute/unmute
- [x] 3.3 Обновить `docs/ARCHITECTURE.md` и `docs/METRICS.md`; проверить, что mute не влияет на `volumeLow/High`

## 4. Уведомления

- [x] 4.1 Убрать `text/subText`/сырые `RemoteViews` из сигнатуры события; покрыть тестом «меняется только прогресс — события нет»
- [x] 4.2 Вынести запись notification-события в `Dispatchers.IO`; проверить, что main-поток не делает fsync
- [x] 4.3 Кэшировать в снимок только `mediaLike`/stock уведомления; покрыть тестом, что личное уведомление не попадает в кэш
- [x] 4.4 Обновить `docs/ARCHITECTURE.md`; проверить, что поведение кэша описано

## 5. Отчёт

- [x] 5.1 Добавить `rated_plays` (`completed + skipped + interrupted`) в `report.py`; `skip_rate` и `completion` считать по нему
- [x] 5.2 Показывать `service_stopped`, `session_lost`, `not_played` отдельными счётчиками; покрыть golden-тестом
- [x] 5.3 `delete_candidates` требует минимум оценённых прослушиваний; покрыть тестом «три service_stopped + один skipped — не кандидат»
- [x] 5.4 Обновить `docs/METRICS.md`; проверить, что формулы совпадают с отчётом

## 6. Стоимость и UX

- [x] 6.1 Кэшировать `probe.hasNotificationAccess()` на 10 секунд; проверить сборкой и lint
- [x] 6.2 Убрать `todayCounts`/`trimOld` с main-потока при старте; проверить сборкой
- [x] 6.3 Ограничить accessibility двумя пакетами; проверить сборкой; окончательно — в 7.2
- [x] 6.4 Исправить повторный bind `TeyesToolkitClient`; проверить сборкой и lint
- [x] 6.5 Увеличить чипы до 48 dp и добавить подтверждение смены срока хранения с числом файлов; проверить сборкой и lint

## 7. Проверка

- [x] 7.1 Прогнать `:app:testDebugUnitTest`, `:app:lintDebug`, `py scripts/test_report.py` и `openspec validate --all`
- [ ] 7.2 Собрать APK и проверить на телефоне: пауза, mute, уведомление с прогрессом, старт службы на паузе

## Workflow follow-up

- После проверки в машине обновить `docs/ROADMAP.md` и заархивировать change.
