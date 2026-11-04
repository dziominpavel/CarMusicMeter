# Tasks

## 1. Главный экран

- [x] 1.1 Убрать `onSnapshot` из `DashboardScreen`/`DashboardActions`, оставить одну кнопку «Экспорт логов» на всю ширину; проверить, что `grep onSnapshot app/src` пуст и проект компилируется
- [x] 1.2 Удалить `takeSnapshot()` из `MainActivity` и лишние импорты; проверить компиляцию `gradlew compileDebugKotlin`

## 2. Снимок при экспорте

- [ ] 2.1 В `LaunchedEffect(showExportDialog, exportRange)` снимать снимок (`reason = "export"`) до `planExport`, обернув в `runCatching` с записью в `CrashLogger`; проверить на телефоне: после открытия диалога в `snapshots/` есть `snapshot-*-export.json`
- [x] 2.2 Показать в `ExportDialog` разбивку «журнал · снимки · crash» из `ExportPlan.files` и строку о свежем снимке; проверить, что сумма по видам равна общему числу файлов
- [x] 2.3 Добавить JVM-тест: снимок за сегодня входит в план любого периода; проверить `gradlew testDebugUnitTest`
- [ ] 2.4 Проверить, что сбой снимка не блокирует экспорт: временно заставить `capture` бросить исключение и убедиться, что диалог открывается и ZIP создаётся

## 3. Тексты

- [x] 3.1 Заменить шаг 3 в «Проверке в машине» на «Нажми «Экспорт логов» и сохрани ZIP на флешку»; проверить на экране настроек
- [x] 3.2 Убрать «снять срез» из `README.md` и `docs/HEADUNIT_TEST.md`; проверить `grep -r "Снять срез"` по репозиторию
- [x] 3.3 Добавить пункт в `## [Unreleased]` changelog; проверить `python scripts/check-version.py`

## 4. Проверка сценария

- [ ] 4.1 Собрать `build-apk.bat debug`, проверить на телефоне: экспорт «30 дней» даёт ZIP со `snapshot-*-export.json` и `manifest.json`, `python scripts/report.py <zip>` не сообщает проблем
- [x] 4.2 Прогнать `openspec validate --all` — exit 0

## Workflow follow-up

- Заархивировать change после проверки на телефоне.
