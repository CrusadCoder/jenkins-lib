# Tasks: debug replace for MR pipelines

## Зависимости между задачами
- Задачи 1-3 должны быть завершены до начала задач 4-7.
- Задачи 4-7 должны быть завершены до начала задач 8-9.
- Задача 10 выполняется параллельно с задачами 8-9 только после фиксации формата control JSON и `fileId`.
- Задачи 11-14 выполняются после завершения задач 4-10.

## 1. Уточнить точки использования файлов
- Проверить, где именно в библиотеке читаются `jobConfiguration.json`, `sonar-project.properties`, `tools/vrunner.json`, `tools/VAParams.json`.
- Зафиксировать, какие из этих файлов нужны только в `pre-stage`, а какие должны быть доступны на downstream agents.
- Подтвердить список стадий, где потребуется восстановление файлов через `unstash`.

## 2. Спроектировать flow подмен
- Зафиксировать итоговый сценарий выполнения:
  - в `pre-stage` определить профиль;
  - проверить доступность `Config File Provider`;
  - попытаться загрузить control JSON;
  - при активном профиле разложить файлы в workspace;
  - считать `jobConfiguration()`;
  - сформировать stash для файлов, нужных на других агентах.
- Зафиксировать downstream flow:
  - перед стадиями, использующими `sonar-project.properties`, `tools/vrunner.json`, `tools/VAParams.json`, выполнять восстановление файлов из stash.

## 3. Подготовить формат Jenkins control JSON
- Утвердить структуру control JSON и фиксированный `fileId`:
  - `jenkins-debug-overrides-control`
- Зафиксировать обязательные поля профиля:
  - `enabled`
  - `replacements`
  - `fileId`
  - `target`
- Зафиксировать целевой набор файлов первой версии:
  - `jobConfiguration.json`
  - `sonar-project.properties`
  - `tools/vrunner.json`
  - `tools/VAParams.json`

## 4. Выделить тестируемую utility-логику
- Создать utility class в `src/.../utils` или аналогичном пакете для логики, которую нужно unit-тестировать вне pipeline-step слоя.
- Вынести в него:
  - `resolveDebugProfileKey`
  - `validateDebugProfile`
  - `validateTargetPath`
  - сборку набора replacements для `configFileProvider`
  - решение, какие файлы должны попадать в stash
- Зафиксировать контракт utility-класса так, чтобы pipeline helper оставался thin orchestration layer.

## 5. Реализовать helper для ранней подмены
- Создать `vars/applyDebugOverridesIfNeeded.groovy`.
- Добавить в helper:
  - вычисление `profileKey` из `env.JOB_NAME`;
  - безопасную проверку доступности `configFileProvider`;
  - загрузку control JSON;
  - поиск профиля;
  - валидацию `replacements`;
  - валидацию `target`;
  - раскладку файлов в workspace;
  - формирование stash для downstream agents;
  - логирование всех ключевых сценариев.
- Зафиксировать имя stash, например `debug-overrides-files`.

## 6. Реализовать helper для восстановления файлов
- Создать `vars/restoreDebugOverridesIfNeeded.groovy`.
- Реализовать в нем:
  - попытку `unstash debug-overrides-files`;
  - безопасный `no-op`, если stash не создавался;
  - понятные сообщения в лог.
- Ограничить использование helper только теми стадиями, где реально нужны подложенные файлы.

## 7. Встроить новую логику в `pipeline1C`
- Обновить [`pipeline1C.groovy`](f:/1C/Projects/jenkins-lib_kyrales/vars/pipeline1C.groovy):
  - в `pre-stage` вызвать `applyDebugOverridesIfNeeded()` до `jobConfiguration()`;
  - оставить остальное текущее поведение без изменений.
- Добавить `restoreDebugOverridesIfNeeded()` в нужные стадии:
  - в `Подготовка ИБ` на `agent1C`;
  - в `BDD сценарии`;
  - в `Синтаксический контроль`;
  - в `Дымовые тесты`;
  - в `YAXUnit тесты`;
  - в `SonarQube` перед `sonarScanner config`.

## 8. Доработать и проверить fallback-логику внутри helper
- Выполнить задачу только после базовой реализации `applyDebugOverridesIfNeeded()`.
- Обеспечить `no-op`, если:
  - `Config File Provider` не установлен;
  - control JSON отсутствует;
  - профиль не найден;
  - профиль выключен.
- Обеспечить fail-fast, если:
  - профиль включен, но control JSON невалиден;
  - у replacement отсутствуют обязательные поля;
  - `target` небезопасен;
  - managed file с указанным `fileId` не может быть разложен.

## 9. Добавить unit tests
- Написать тесты на вычисление `profileKey`.
- Написать тесты на fallback при отсутствии `Config File Provider`.
- Написать тесты на fallback при отсутствии control JSON.
- Написать тесты на отсутствие подмен при отсутствии профиля.
- Написать тесты на отсутствие подмен при `enabled=false`.
- Написать тесты на валидацию `target`.
- Написать тесты на построение набора entries для `configFileProvider`.
- Написать тесты на решение, какие файлы должны попадать в stash.

## 10. Добавить интеграционные тесты
- Проверить сценарий без `Config File Provider`.
- Проверить сценарий без control JSON.
- Проверить сценарий с активным профилем и подменой `jobConfiguration.json`.
- Проверить сценарий с подменой `sonar-project.properties`.
- Проверить сценарий с подменой `tools/vrunner.json` и `tools/VAParams.json`.
- Проверить перенос файлов между агентами через `stash/unstash`.
- Проверить аварийный сценарий с отсутствующим `fileId`.

## 11. Подготовить Jenkins-часть
- Создать managed file control JSON с `fileId`:
  - `jenkins-debug-overrides-control`
- Создать managed files для первой версии:
  - `debug-ci-uh-mr-jobConfiguration`
  - `debug-ci-uh-mr-sonar-properties`
  - `debug-ci-uh-mr-vrunner`
  - `debug-ci-uh-mr-vaparams`
- Заполнить профиль `ci_uh_MR` в control JSON.
- Проверить соответствие `fileId` между control JSON и Jenkins managed files.
- Проверить scope managed files:
  - доступны ли они из folder `ci_uh_MR`;
  - не ограничены ли они чужим folder scope;
  - достаточно ли прав у job на чтение этих managed files.

## 12. Провести ручную проверку
- Проверить build на Jenkins-контуре без плагина.
- Проверить build с отсутствующим control JSON.
- Проверить build с выключенным профилем.
- Проверить build с включенным профилем.
- Проверить replay/повторный запуск build в существующем workspace.
- Проверить, что нужный MR использует подложенные:
  - `jobConfiguration.json`
  - `sonar-project.properties`
  - `tools/vrunner.json`
  - `tools/VAParams.json`
- Проверить, что прикладной MR-репозиторий не изменяется.

## 13. Провести review и согласование
- Подготовить изменения в `pipeline1C.groovy` к отдельному review, так как это центральный shared pipeline.
- Отдельно проверить, что интеграция `restoreDebugOverridesIfNeeded()` не меняет поведение проектов без debug-профиля.
- Согласовать naming helper-ов, stash name и `fileId` control JSON.

## 14. Обновить документацию
- Синхронизировать `README.md` в `docs/feat_debug_replace` с итоговой реализацией.
- При необходимости добавить в основной проектный `README.md` короткое описание механизма debug replace и его ограничений.
