## ADDED Requirements

### Requirement: Диагностика UI штатного плеера

Приложение MUST сохранять диагностику UI штатного плеера в снимке и в событии
`stock_player_ui` при изменении состояния или по кнопке «Снять срез». Диагностика
MUST содержать все видимые тексты без фильтра, для каждого узла — `className`,
`viewIdResourceName`, `text`, `contentDescription`, `rangeInfo`, а также список
окон с пакетом, заголовком, признаками `active`/`focused`. Приложение MUST
ограничивать число узлов и MUST NOT писать событие на каждом тике без изменений.

#### Scenario: Плеер в split-screen

- **WHEN** пользователь делает снимок, когда плеер виден рядом с навигатором
- **THEN** снимок содержит окно `com.syu.music`, его узлы и `rangeInfo` прогресса

#### Scenario: Переходный экран

- **WHEN** плеер открылся и первое чтение вернуло заголовок папки
- **THEN** `stock_player_ui` содержит все тексты и признак, что парсер отклонил состояние

#### Scenario: Повторный тик

- **WHEN** UI плеера не изменился с прошлого тика
- **THEN** повторное событие `stock_player_ui` не пишется

### Requirement: Диагностика потери источника

Приложение MUST писать событие `capture_gap` при потере и восстановлении окна
штатного плеера. Событие MUST содержать `active`, причину, последний известный
трек, `isMusicActive` и список видимых окон. Событие MUST писаться один раз на
границу разрыва и MUST NOT заменять `track_ended` или `track_progress`.

#### Scenario: Плеер ушёл в фон

- **WHEN** окно плеера перестало быть видимым, а музыка играет
- **THEN** появляется `capture_gap` с `active = true` и последним треком

#### Scenario: Плеер вернулся

- **WHEN** окно плеера снова стало видимым
- **THEN** появляется `capture_gap` с `active = false` и причиной восстановления

### Requirement: Статус подключения notification listener

Снимок диагностики MUST содержать отдельное поле `notificationListenerConnected`,
отражающее фактическое подключение `NotificationListenerService`, а не только
выданное разрешение. Приложение MUST обновлять статус при подключении и
отключении listener.

#### Scenario: Разрешение есть, listener не подключён

- **WHEN** доступ к уведомлениям выдан, но listener не подключён
- **THEN** снимок содержит `notificationAccess = true` и `notificationListenerConnected = false`

#### Scenario: Listener подключён

- **WHEN** `NotificationListenerService` подключился
- **THEN** снимок содержит `notificationListenerConnected = true`
