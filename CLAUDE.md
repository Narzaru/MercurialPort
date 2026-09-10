# CLAUDE.md

Плагин **Mercurial Port** (репозиторий `Narzaru/MercurialPort`, артефакт `mercurial-port`) —
инструменты Mercurial для JetBrains IDE (используется в Rider).

## Сборка и проверка

```bash
./gradlew compileKotlin     # быстрая проверка компиляции
./gradlew test              # юнит-тесты (нужна сеть: junit и test-framework не в репозитории)
./gradlew buildPlugin       # артефакт build/distributions/mercurial-port-<version>.zip
./gradlew runIde            # запуск IDE с плагином
```

Версия задаётся в `gradle.properties`, целевая платформа — `intellijIdea("2025.3")` в
`build.gradle.kts`, версия Gradle-плагина платформы — в `settings.gradle.kts`.

Сборка для установки — через скрипт:

```powershell
.\build-plugin.ps1 -Version 1.0.8  # версия явно
.\build-plugin.ps1 -SkipBump       # та же версия, если она уже поднята
.\build-plugin.ps1                 # patch +1 и buildPlugin
```

Версия в `gradle.properties` держится **на единицу больше залитой на GitHub** — это номер
готовящегося релиза, того, что сейчас в `[Unreleased]` в CHANGELOG.

Сборка после правок делается **в фоне** и **первым делом**: как только код компилируется,
запускается сборка, а CHANGELOG и этот файл правятся уже пока она идёт.

`compileKotlin` артефакт **не** обновляет: если правки проверяются в установленном плагине, нужен
`buildPlugin`.

Установка для проверки: `Settings → Plugins → ⚙ → Install Plugin from Disk…` → zip из
`build/distributions`, затем перезапуск IDE.

Ошибки времени выполнения — в `%LOCALAPPDATA%\JetBrains\Rider<версия>\log\idea.log`.

## Структура

Корень пакетов — `com.narzaru.mercurial`.

| Пакет | Содержимое |
|---|---|
| `hg` | Запуск `hg`, разбор и декодирование вывода, пути, настройки плагина |
| `changes` | Тул-окно **Hg Changes** — дерево изменений, ревью-отметки, TODO-режим |
| `diff` | Дифф-вкладки плагина (`HgDiffTabManager`) |
| `history` | Тул-окно **Hg File History** — `hg log -f` по файлу |
| `export` | Действия «Hg Export» в меню Tools |
| `status` | Статусы файлов вне тул-окна — буква состояния в заголовке вкладки редактора |
| `model` | Модели данных (`HgFileItem`, `HgDiffStat`, режимы) |

Регистрация тул-окон, страницы настроек и действий — `src/main/resources/META-INF/plugin.xml`.

Панели (`HgChangesPanel`, `HgFileHistoryPanel`) отвечают только за UI и оркестровку. Всё, что можно
проверить без запущенной IDE, вынесено отдельно и покрыто тестами в `src/test/kotlin`:

- разбор вывода `hg` — `HgStatusParser`, `HgDiffStatParser`, `HgLogParser`, `HgOutputDecoder`;
- пути — `HgPaths`;
- состояние окна изменений — `ReviewState` (за интерфейсом `ReviewedPathsStore`), `PathFilter`,
  `ChangesSettings`, `StatusTextFormatter`, `BranchScope`, `BranchRevisions`;
- сборка диффа — `HgPatchApplier`, `FragmentScope`;
- дерево и отрисовка — `ChangesTreeBuilder`, `ChangesTreeRenderers`, `TextFitter`.

Новую логику имеет смысл добавлять туда же, а не в панель: внутри Swing-компонента её не вызвать
из теста.

## Стиль

- **Всё в коде — по-английски**: идентификаторы, строки интерфейса (подписи и подсказки действий,
  диалоги, статусы, страница настроек), CHANGELOG. Русский остаётся только в переписке и в этом
  файле.
- **Комментарии в коде не пишутся.** Ни поясняющие, ни KDoc, ни заголовки секций. Если строку
  нельзя понять без комментария — переписывается строка (имя, выделенная функция), а не
  добавляется комментарий.
- CHANGELOG ведётся в разделе `[Unreleased]` (`Added` / `Changed`), короткими записями по сути:
  без примеров, цифр и имён файлов из отладки.
