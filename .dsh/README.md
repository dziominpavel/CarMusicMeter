# `.dsh/` — окружение DeepSeek Harness для этого проекта

Каталог читает DSH (DeepSeek Harness). Правила проекта живут отдельно, в
[`AGENTS.md`](../AGENTS.md) — DSH загружает их сам, дублировать здесь не нужно.

## Навыки

Навык — это каталог `.dsh/skills/<name>/SKILL.md` с YAML-фронтматтером. Обязательны
`name` (kebab-case, совпадает с именем каталога) и `description`; опционально
`whenToUse`. Прочитав каталог, модель вызывает навык по имени; человек видит те же
навыки как слэш-команды `/name`.

Изменения подхватываются на лету, перезапуск не нужен. Файлы внедрённых ресурсов
(`references/`, `scripts/`, `assets/`) не следятся вотчером — правку тела навыка
достаточно сохранить.

## Что здесь лежит

| Каталог | Откуда | Что делает |
|---|---|---|
| `openspec-*` (6) | копия `.opencode/skills/` | Рабочий процесс OpenSpec: explore → propose → apply → archive, плюс sync и update |
| `opsx-*` (6) | написано вручную | Тонкие алиасы `/opsx-propose` и т.д.: загружают соответствующий `openspec-*` и передают ему ввод |

В DSH нет слэш-команд как файлов (в отличие от `.opencode/commands/`), поэтому
привычные `/opsx-*` сохранены именно так. Алиас намеренно не пересказывает
workflow: единственный источник правды — `openspec-*`, алиас только перенаправляет.

## Обновление после `openspec update`

Копии `openspec-*` сделаны из `.opencode/skills/` (OpenSpec CLI 1.16.0) и не
обновляются сами. После `openspec update`, обновившего `.opencode/skills/`,
синхронизировать так:

```powershell
Remove-Item .dsh\skills\openspec-* -Recurse -Force
Copy-Item .opencode\skills\* .dsh\skills\ -Recurse -Force
```

Проверить, что всё на месте:

```powershell
Get-ChildItem .dsh\skills -Directory | ForEach-Object { $_.Name }
```

## Как добавить свой навык

```powershell
New-Item -ItemType Directory .dsh\skills\my-skill
```

```markdown
---
name: my-skill
description: Одна строка о том, когда это применять.
---

Инструкции навыка.
```

## Корни, которые DSH сканирует

По приоритету: `.dsh/skills` (этот каталог) → `.agents/skills` → `customSkillDirs`
из профиля → `~/.dsh/skills` → `~/.agents/skills`. Сканируется **только верхний
уровень**: `<name>/SKILL.md` или плоский `<name>.md`. Вложенные `**/SKILL.md`
намеренно игнорируются.

## Плагины

Локальные плагины DSH лежат в `.dsh/plugins/<name>/` — это обычные пакеты,
которые ставятся в профиль ссылкой (`install_bundle`), а не копией.

| Каталог | Что делает |
|---|---|
| `dsh-ru-locale` | Русский язык для веб-интерфейса DSH: 58 пространств имён, 2668 строк. Подробности и сборка перевода — в его `README.md` |

Установка и переустановка (после правки `client.js` или переезда каталога):

```text
plugin_manager install_bundle target=C:\projects\CarMusicMeter\.dsh\plugins\dsh-ru-locale
```

Профиль держит на пакет junction; если каталог переместить без переустановки,
ссылка станет битой и плагин перестанет загружаться.

## Профиль

Модель, права и плагины — это глобальный профиль DSH
(`~/.dsh/profiles/<name>/cordis.patch.yml`), а не файл в проекте. Дерево профиля
целиком: `dsh --dump-config --profile web`.
