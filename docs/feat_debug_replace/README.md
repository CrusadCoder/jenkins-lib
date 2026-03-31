# Feature: debug replace for MR pipelines

## Краткое описание
Нужно реализовать в shared library механизм временной подмены `jobConfiguration.json`, `sonar-project.properties`, `tools/vrunner.json` и `tools/VAParams.json` в workspace Jenkins для multibranch MR-пайплайнов без изменений прикладного MR-репозитория.

Цель механизма:
- включать подмену централизованно через Jenkins Managed Files;
- активировать ее только для нужного multibranch folder, например `ci_uh_MR`;
- выполнять подмену до чтения `jobConfiguration()` в `pipeline1C.groovy`;
- не менять поведение обычных branch/job сборок, если профиль не настроен или выключен.

## Проблема
Сейчас библиотека читает конфигурацию проекта из workspace:
- базовая конфигурация берется из `resources/globalConfiguration.json`;
- поверх нее накладывается `jobConfiguration.json` из корня рабочей директории;
- отдельные шаги используют файлы из `tools/*.json`, если они существуют в workspace;
- шаг SonarQube использует `sonar-project.properties` из текущего workspace агента.

Для MR-сборок это неудобно в случаях, когда:
- нельзя менять файлы в прикладном репозитории;
- нужно временно подложить отладочные настройки;
- нужно запускать это на выбранном MR в существующей multibranch MR job;
- нет удобного пользовательского параметра запуска на каждый MR build.

## Целевое поведение
Для MR job из multibranch pipeline библиотека должна уметь:
1. Определить ключ профиля подмены по Jenkins job path.
2. Найти для этого ключа профиль в одном Jenkins managed JSON.
3. Если профиль включен, разложить набор внешних файлов в workspace по заданным путям.
4. После этого продолжить выполнение штатного `pipeline1C()`, который увидит уже подложенные `jobConfiguration.json`, `sonar-project.properties`, `tools/vrunner.json` и `tools/VAParams.json`.

Если профиль отсутствует или выключен:
- библиотека ничего не меняет;
- пайплайн работает как сейчас.

## Ограничения
- Нельзя вносить изменения в прикладной MR-репозиторий.
- Изменения допустимы только в shared library и Jenkins configuration.
- Подмена должна жить только в рамках workspace конкретного build.
- Решение не должно требовать изменения Jenkinsfile в прикладном проекте.
- Решение должно быть совместимо с текущим вызовом `pipeline1C()`.
- Если plugin `Config File Provider` не установлен или недоступен в текущем Jenkins, пайплайн должен продолжать работу в штатном режиме без debug-подмен.

## Основная идея
Использовать Jenkins plugin `Config File Provider` и один управляющий managed JSON-файл, содержащий:
- список профилей;
- флаг включения профиля;
- список файлов для подмены;
- `fileId` Jenkins managed file для каждого заменяемого файла;
- путь назначения в workspace.

### Почему не использовать плоские ключи вида `Debug_ci_uh_MR_ИмяФайла`
Этот формат менее удобен для сопровождения:
- сложно валидировать;
- сложно расширять;
- неудобно хранить список файлов и флаг включения вместе;
- повышается риск ошибки в имени ключа.

Структурированный JSON проще читать, тестировать и логировать.

При этом feature должна быть безопасной для Jenkins-контуров, где плагин отсутствует: в таком случае механизм подмены не активируется, а библиотека работает по старому поведению.

## Место интеграции
Точка входа:
- `vars/pipeline1C.groovy`

Интеграция должна происходить в `pre-stage`, до строки:

```groovy
config = jobConfiguration() as JobConfiguration
```

Порядок в `pre-stage` должен стать таким:
1. Инициализация контекста job.
2. Попытка применить debug overrides.
3. Чтение `jobConfiguration()`.
4. Расчет `agent1C`, `agentEdt`, `RepoUtils.computeRepoSlug(...)`.

Это важно, потому что `jobConfiguration()` должен видеть уже подложенный `jobConfiguration.json`, если он заменяется.

Важно: одной подмены только в `pre-stage` недостаточно для файлов, которые читаются на других агентах и в других workspace.

Для первой версии нужно зафиксировать две разные стратегии:
- `jobConfiguration.json` подменяется в `pre-stage` до вызова `jobConfiguration()`;
- `sonar-project.properties`, `tools/vrunner.json`, `tools/VAParams.json` должны доставляться в workspace того агента, где они реально используются.

Принятое решение для первой версии:
- после загрузки файлов в `pre-stage` они должны быть дополнительно сохранены через `stash`;
- перед использованием на нужных стадиях они должны восстанавливаться через `unstash` в workspace текущего агента.
- наличие подмен между стадиями не передается через отдельную environment variable или global pipeline variable;
- `restoreDebugOverridesIfNeeded()` всегда пытается выполнить `unstash` внутри `try/catch`;
- если stash отсутствует, helper завершает работу как `no-op` и пишет это в лог;
- если `unstash` завершается другой ошибкой, не связанной с отсутствием stash, это считается ошибкой выполнения и пайплайн должен падать.

Это решение обязательно, потому что стадии `pre-stage`, `Подготовка`, `Проверка качества` и `SonarQube` выполняются на разных Jenkins agents.

## Предлагаемые изменения в коде

### 1. Новый helper для подмен
Добавить новый pipeline helper, например:
- `vars/applyDebugOverridesIfNeeded.groovy`

Назначение helper:
- сначала проверить, доступен ли `configFileProvider`;
- определить ключ профиля;
- получить control JSON через `configFileProvider`;
- прочитать и разобрать его;
- если найден активный профиль, разложить перечисленные файлы в workspace;
- вывести понятные сообщения в лог;
- вернуть объект результата или `boolean`, показывающий, были ли применены подмены.

### 2. Вызов helper из `pipeline1C`
В `pre-stage` добавить вызов:

```groovy
applyDebugOverridesIfNeeded()
config = jobConfiguration() as JobConfiguration
```

Подмена должна происходить до чтения конфигурации.

После успешной подмены helper также должен подготовить stash с файлами, которые понадобятся на других агентах:
- `sonar-project.properties`
- `tools/vrunner.json`
- `tools/VAParams.json`

`jobConfiguration.json` в stash включать необязательно, так как он используется только для раннего построения `config`.

Если в активном профиле нет ни одного файла из downstream-набора, stash не создается вообще.

### 3. Выделить логику разрешения profile key
Добавить утилитарный метод, который из `env.JOB_NAME` получает профиль.

Правило для первой версии:
- использовать предпоследний сегмент пути Jenkins job.

Пример:
- `CPC/ci_uh_MR/feature_171_1_uzd_4_zakupka...`
- профиль: `ci_uh_MR`

Причина выбора:
- на скриншоте видно `Full folder name: CPC/ci_uh_MR`;
- имя конкретного MR job меняется, имя папки-профиля стабильно.

### 4. Использовать один control JSON
Нужен один Jenkins managed file, например:
- `jenkins-debug-overrides-control`

Он хранит описание профилей.

Пример структуры:

```json
{
  "profiles": {
    "ci_uh_MR": {
      "enabled": true,
      "description": "Отладочные подмены для MR пайплайнов УХД",
      "replacements": [
        {
          "fileId": "debug-ci-uh-mr-jobConfiguration",
          "target": "jobConfiguration.json"
        },
        {
          "fileId": "debug-ci-uh-mr-sonar-properties",
          "target": "sonar-project.properties"
        },
        {
          "fileId": "debug-ci-uh-mr-vrunner",
          "target": "tools/vrunner.json"
        },
        {
          "fileId": "debug-ci-uh-mr-vaparams",
          "target": "tools/VAParams.json"
        }
      ]
    }
  }
}
```

## Подробная логика `applyDebugOverridesIfNeeded`

### Шаг 1. Определение ключа профиля
Источник:
- `env.JOB_NAME`

Алгоритм:
1. Разбить строку по `/`.
2. Если сегментов меньше 2, считать, что профиль определить нельзя.
3. Взять предпоследний сегмент.
4. Если сегмент пустой, подмену не выполнять.

Примеры:
- `CPC/ci_uh_MR/MR-1101` -> `ci_uh_MR`
- `Folder/ci_erp_MR/some-branch` -> `ci_erp_MR`

### Шаг 2. Проверка доступности `Config File Provider`
Перед любыми действиями проверить, что шаг `configFileProvider` доступен в текущем Jenkins.

Требование для реализации:
- проверка должна быть безопасной и не ломать выполнение на Jenkins без плагина;
- если плагин недоступен, helper должен записать понятное сообщение в лог и завершиться как `no-op`;
- отсутствие плагина не считается ошибкой пайплайна.

Принятое решение для первой версии:
- отдельный probe-вызов не используется;
- проверка доступности плагина совмещается с первой попыткой загрузить control JSON;
- доступность плагина проверяется практическим вызовом `configFileProvider` с реальным `fileId` control-файла внутри `try/catch`;
- helper использует отдельную функцию классификации ошибки, например `shouldTreatConfigFileProviderErrorAsMissingPlugin(Exception e)`;
- классификация выполняется по типу exception и, при необходимости, по тексту сообщения;
- если ошибка указывает на отсутствие pipeline step или недоступность plugin, helper считает, что plugin недоступен, пишет `skip` в лог и возвращает `false`;
- если ошибка указывает на отсутствие managed file `jenkins-debug-overrides-control`, это трактуется как отсутствие control JSON и тоже считается `no-op` для первой версии;
- только остальные ошибки `configFileProvider`, не относящиеся к отсутствию plugin или control file, считаются ошибками выполнения и должны валить пайплайн.

Ожидаемое поведение:
- plugin есть -> продолжаем сценарий debug-подмен;
- plugin нет -> возвращаем `false` и оставляем текущее поведение без изменений.

Пример сообщения:
- `Debug overrides: Config File Provider plugin is unavailable, skip`

### Шаг 3. Загрузка control-файла
Через `configFileProvider` положить control JSON во временный путь workspace, например:
- `.jenkins/debug-overrides.json`

После этого:
- прочитать файл через `readJSON file: ...`

Принятое решение для первой версии:
- managed file для control JSON регистрируется в Jenkins с фиксированным `fileId`:
  - `jenkins-debug-overrides-control`
- отсутствие control JSON или невозможность разложить его из-за того, что сам managed file не создан в Jenkins, трактуется как `no-op`, если профиль еще не определен как активный обязательный сценарий;
- в данной версии feature не вводит обязательных профилей, поэтому отсутствие control JSON всегда трактуется как `no-op` с логированием.

### Шаг 4. Поиск профиля
В разобранном JSON:
- найти `profiles[profileKey]`

Варианты:
- профиль не найден -> лог + `return false`
- профиль найден, но `enabled != true` -> лог + `return false`
- профиль найден и включен -> перейти к подмене

### Шаг 5. Валидация профиля
Проверить:
- `replacements` существует;
- `replacements` массив;
- каждый элемент содержит непустые `fileId` и `target`;
- `target` относительный путь внутри workspace;
- не допускаются абсолютные пути;
- не допускаются `..` в пути, выходящие за workspace.
- ограничений на количество элементов в `replacements` для первой версии не вводится.

Если профиль включен, но структура невалидна:
- падать с понятной ошибкой, потому что это явная ошибка конфигурации Jenkins.

### Шаг 6. Разкладка файлов
Сформировать список для `configFileProvider`:
- каждый `fileId` раскладывается в соответствующий `targetLocation`

Пример:
- `debug-ci-uh-mr-vrunner` -> `tools/vrunner.json`

До раскладки:
- при необходимости создать родительские каталоги, если плагин сам их не создает стабильно;
- для `target` в подпапках использовать `createDir` или эквивалентный шаг.

После раскладки:
- вывести список реально подложенных файлов.
- если были подложены `sonar-project.properties`, `tools/vrunner.json`, `tools/VAParams.json`, сформировать stash для переноса между агентами.

Имя stash для первой версии нужно зафиксировать заранее, например:
- `debug-overrides-files`

В stash включать только реально подложенные файлы из набора:
- `sonar-project.properties`
- `tools/vrunner.json`
- `tools/VAParams.json`

Если реально подложенный downstream-набор пуст:
- stash не создается;
- это штатный сценарий, а не ошибка.

### Шаг 7. Завершение
Вернуть:
- `true`, если подмены были выполнены;
- `false`, если профиль не найден или выключен.

## Правила логирования
В логах должно быть видно:
- значение `env.JOB_NAME`;
- вычисленный `profileKey`;
- найден ли профиль;
- включен ли профиль;
- список `target`, которые были подложены.

Примеры сообщений:
- `Debug overrides: Config File Provider plugin is unavailable, skip`
- `Debug overrides: control file is unavailable, skip`
- `Debug overrides: resolved profile key = ci_uh_MR`
- `Debug overrides: profile ci_uh_MR not found, skip`
- `Debug overrides: profile ci_uh_MR is disabled, skip`
- `Debug overrides: applying 4 replacement(s)`
- `Debug overrides: wrote tools/vrunner.json from managed file debug-ci-uh-mr-vrunner`
- `Debug overrides: wrote sonar-project.properties from managed file debug-ci-uh-mr-sonar-properties`
- `Debug overrides: stashed files for downstream agents`
- `Debug overrides: restored files from stash`
- `Debug overrides: downstream stash is absent, skip restore`

Не печатать:
- содержимое JSON;
- секреты;
- полный текст конфигурационных файлов.

## Поведение при ошибках
Для первой версии рекомендуется жесткий режим только если профиль найден и включен.

### No-op сценарии
Не падать, если:
- `Config File Provider` plugin не установлен или недоступен;
- `JOB_NAME` не удалось разобрать;
- control JSON отсутствует или не настроен в Jenkins;
- профиль не найден;
- профиль найден, но выключен.

### Fail-fast сценарии
Падать с понятным сообщением, если:
- профиль включен, но control JSON невалиден;
- профиль включен, но в `replacements` отсутствуют обязательные поля;
- `target` небезопасен;
- `configFileProvider` не смог разложить указанный `fileId`;
- `unstash` завершился ошибкой, не связанной с отсутствием stash.

Обоснование:
если Jenkins-конфигурация специально включила профиль, значит ожидалось применение подмен; тихое продолжение может скрыть проблему.

## Контракт первой версии
Поддержать только следующие возможности:
- один control JSON;
- безопасный fallback при отсутствии `Config File Provider`;
- безопасный fallback при отсутствии control JSON;
- поиск профиля по предпоследнему сегменту `JOB_NAME`;
- замена любого количества файлов в workspace;
- замена как файлов из корня workspace, так и файлов из подпапки `tools`;
- перенос подложенных файлов между Jenkins agents через `stash/unstash`;
- отсутствие изменений в MR-репозитории;
- отсутствие пользовательских build parameters.

Не включать в первую версию:
- несколько источников control JSON;
- fallback на системные env vars;
- шаблоны/маски в `target`;
- сложную иерархию наследования профилей;
- merge содержимого JSON на уровне полей.

Подмена должна быть именно файловой, а не логической merge-операцией.

## Изменения в Jenkins
Нужно подготовить Jenkins configuration.

### Плагин
Убедиться, что установлен:
- `Config File Provider`

Если плагин отсутствует:
- feature автоматически не используется;
- пайплайн должен работать по старому сценарию без изменений.

### Managed files
Создать:
1. Один control JSON:
   - `fileId`: `jenkins-debug-overrides-control`
   - содержимое: JSON-конфигурация профилей подмен
2. Отдельные managed files для подменяемых файлов:
   - `debug-ci-uh-mr-jobConfiguration`
   - `debug-ci-uh-mr-sonar-properties`
   - `debug-ci-uh-mr-vrunner`
   - `debug-ci-uh-mr-vaparams`

Имена `fileId` можно выбрать другие, но они должны совпадать с control JSON.

Важно:
- managed files должны быть созданы в таком scope Jenkins, который доступен multibranch job `ci_uh_MR`;
- при настройке нужно отдельно проверить folder/global scope и права доступа к этим managed files.

### Пример первой конфигурации
Для `ci_uh_MR` включить профиль и прописать минимальный набор замен:
- `jobConfiguration.json`
- `sonar-project.properties`
- `tools/vrunner.json`
- `tools/VAParams.json`

Это и есть целевой набор файлов для первой версии.

## Предлагаемая структура кода

### Новый helper
`vars/applyDebugOverridesIfNeeded.groovy`

Ответственность:
- orchestration полного сценария.
- загрузка и подмена файлов в `pre-stage`;
- формирование stash для файлов, которые нужны на других агентах.

### Новый helper для downstream stages
Добавить отдельный helper, например:
- `vars/restoreDebugOverridesIfNeeded.groovy`

Ответственность:
- выполнять `unstash debug-overrides-files`, если stash был сформирован;
- не падать, если подмены не активировались и stash отсутствует;
- писать в лог как успешное восстановление, так и `skip`, если stash отсутствует;
- падать при любой ошибке `unstash`, кроме сценария отсутствующего stash;
- вызываться в начале тех stages, где нужны `sonar-project.properties`, `tools/vrunner.json`, `tools/VAParams.json`.

Конкретные точки вызова в `pipeline1C.groovy` для первой версии:
- в стадии `Подготовка ИБ` на агенте `agent1C` перед вложенными шагами, которые могут использовать `tools/vrunner.json` и `tools/VAParams.json`;
- в стадии `BDD сценарии` перед выполнением шагов BDD;
- в стадии `Синтаксический контроль` перед выполнением syntax-check;
- в стадии `Дымовые тесты` перед выполнением smoke;
- в стадии `YAXUnit тесты` перед выполнением YAXUnit;
- в стадии `SonarQube` перед вызовом `sonarScanner config`.

Такой набор точек выбран, чтобы восстановление происходило один раз на каждый agent/workspace, а не перед каждым отдельным step.

### Внутренние методы/helper-утилиты
Можно разместить в том же файле или в `src/.../utils`, если нужно тестировать отдельно:
- `resolveDebugProfileKey(String jobName)`
- `loadDebugOverridesConfig(String controlFileId)`
- `validateDebugProfile(Map profile)`
- `validateTargetPath(String target)`
- `buildConfigFileProviderEntries(List replacements)`
- `shouldTreatConfigFileProviderErrorAsMissingPlugin(Exception e)`

Для unit-тестов полезно вынести часть логики из pipeline-step слоя в обычный utility class.

## Тестирование

### Unit tests
Добавить unit-тесты на:
- отсутствие подмен и корректный `skip`, если `Config File Provider` недоступен;
- корректное вычисление `profileKey` из `JOB_NAME`;
- отсутствие подмен при отсутствии профиля;
- отсутствие подмен при `enabled=false`;
- ошибку при невалидной структуре `replacements`;
- ошибку при абсолютном `target`;
- ошибку при `target` с выходом за workspace;
- корректное построение списка `configFileProvider` entries.

### Интеграционные tests
Сценарии:
1. `pipeline1C` без установленного `Config File Provider`:
   - выполняется текущая логика;
   - helper завершает работу как `no-op`;
   - `jobConfiguration()` читает штатный файл.
2. `pipeline1C` без control JSON:
   - выполняется текущая логика;
   - helper завершает работу как `no-op`;
   - пайплайн не падает.
3. `pipeline1C` без debug-профиля:
   - выполняется текущая логика;
   - `jobConfiguration()` читает штатный файл.
4. `pipeline1C` с включенным профилем:
   - helper выполняется до `jobConfiguration()`;
   - подложенный `jobConfiguration.json` реально влияет на итоговый `config`.
5. Подмена `sonar-project.properties`:
   - файл попадает в stash в `pre-stage`;
   - на агенте `sonar` выполняется `unstash`;
   - SonarScanner использует внешний файл из workspace.
6. Подмена `tools/vrunner.json` и `tools/VAParams.json`:
   - файлы попадают в stash в `pre-stage`;
   - на агенте, где выполняется шаг с `vanessa-runner`, выполняется `unstash`;
   - шаги используют именно отладочные настройки.
7. Профиль включен, но `fileId` отсутствует:
   - пайплайн падает с понятной ошибкой.

### Ручная проверка в Jenkins
Проверить на реальном `ci_uh_MR`:
- без профиля или при `enabled=false` ничего не меняется;
- при `enabled=true` видны ожидаемые log messages;
- выбранный MR запускается с подложенными JSON;
- при replay или повторном запуске build поверх существующего workspace файлы раскладываются повторно и перезаписывают предыдущее содержимое;
- после завершения build никаких изменений в git-репозитории нет.

## Критерии приемки
Реализация считается завершенной, если:
- MR-репозиторий не требует никаких изменений;
- при отсутствии `Config File Provider` пайплайн продолжает работать как раньше;
- при отсутствии control JSON пайплайн продолжает работать как раньше;
- для multibranch job папки `ci_uh_MR` можно включить/выключить подмену централизованно через Jenkins;
- подменяются `jobConfiguration.json`, `sonar-project.properties`, `tools/vrunner.json` и `tools/VAParams.json`;
- `sonar-project.properties`, `tools/vrunner.json` и `tools/VAParams.json` доступны на тех агентах, где они реально используются;
- `pipeline1C()` использует подложенные файлы без изменения текущего публичного способа вызова;
- обычные job, не имеющие профиля, продолжают работать без изменений;
- ошибки конфигурации профиля диагностируются понятными сообщениями.

## Риски и меры
Риск:
пайплайн упадет на Jenkins-контуре, где плагин не установлен.

Мера:
- сначала проверять доступность `configFileProvider`;
- при отсутствии плагина всегда делать `no-op`;
- покрыть это отдельным тестом.

Риск:
control JSON не создан в Jenkins, и feature начнет валить все сборки.

Мера:
- отсутствие control JSON трактовать как `no-op`;
- логировать это как отдельный сценарий;
- покрыть отдельным тестом.

Риск:
неверно определить `profileKey` из `JOB_NAME`.

Мера:
- покрыть unit-тестами;
- залогировать `JOB_NAME` и вычисленный ключ;
- при необходимости сделать helper, который легко адаптируется под другую схему Jenkins path.

Риск:
плагин не создает промежуточные каталоги.

Мера:
- явно создавать родительские папки перед раскладкой.

Риск:
подложенные файлы останутся только в workspace `pre-stage` и не попадут на другие агенты.

Мера:
- после подмены формировать stash;
- перед использованием файлов на downstream agents выполнять `unstash`;
- покрыть это интеграционным тестом.

Риск:
при replay или повторном запуске build в старом workspace останутся файлы от предыдущей подмены.

Мера:
- считать подмену идемпотентной;
- при каждом запуске всегда заново раскладывать файлы поверх существующих;
- зафиксировать это как ожидаемое поведение.

Риск:
тихая подмена не тех файлов.

Мера:
- логировать все `target`;
- валидировать относительность путей;
- запретить абсолютные пути и traversal.

## Дальнейшие расширения
Вне первой версии можно рассмотреть:
- отдельный глобальный флаг полного отключения feature;
- поддержку профилей по regex или по полному `JOB_NAME`;
- поддержку нескольких control JSON для разных Jenkins folder;
- dry-run режим только с логированием.

## Итоговое решение
В shared library добавляется ранний шаг автоподмены файлов в workspace по профилю Jenkins folder. Профиль определяется из `env.JOB_NAME`, описание профиля хранится в одном Jenkins managed JSON, а сами файлы подмены выдаются через `Config File Provider`. Это позволяет запускать выбранный MR через существующую multibranch MR job с отладочными настройками без каких-либо изменений в прикладном репозитории.
