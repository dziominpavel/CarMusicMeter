# Tasks

## 1. Единая рамка экранов

- [x] 1.1 Добавить `Modifier.screenFrame()` в `ui/AppComponents.kt` (фон + `WindowInsets.safeDrawing`) — проверка: `:app:compileReleaseKotlin` без ошибок
- [x] 1.2 Перевести на рамку «Отчёт», «Все треки», «Качество данных», карточку трека, настройки, диагностику и пульт — проверка: в `ui/` не остаётся ни одной копии `.fillMaxSize()` + `.windowInsetsPadding(`
- [x] 1.3 Убрать неиспользуемые импорты после перехода — проверка: сборка без предупреждений об unused import

## 2. Период отчёта по умолчанию

- [x] 2.1 `ReportPeriod.fromTag(null)` и неизвестный тег → `ALL` — проверка: `ReportPeriodTest`
- [x] 2.2 Согласовать `ReportRepository.lastPeriod` и комментарий в `Prefs.reportPeriod` — проверка: компиляция, тесты репозитория
- [x] 2.3 Подсказка в отчёте при меньше пяти прослушиваний и в пустом списке «Все треки» — проверка: текст виден при периоде «7 дней» без поездок

## 3. Проверки

- [x] 3.1 `./gradlew :app:testDebugUnitTest` — BUILD SUCCESSFUL
- [x] 3.2 `py scripts/check-version.py` (exit 0) и `openspec validate --all` (0 failed)
- [x] 3.3 Release APK через `build-apk.bat` — файл `dist/CarMusicMeter-release-latest.apk`, подсказка присутствует в dex
- [ ] 3.4 Проверка на магнитоле: кнопка «Назад» нажимается на «Анализе», «Все треки», «Качество данных» и карточке трека; отчёт открывается за всю историю; списки не обрезаны снизу

## 4. Changelog

- [x] 4.1 Пункты в пользовательском блоке `## [Unreleased]` (`Исправлено`, `Изменено`) — проверка: `check-version.py` проходит формат-контроль

## Workflow follow-up

- После проверки на магнитоле — заархивировать change (`openspec archive`) и синхронизировать дельты в основные спеки.
