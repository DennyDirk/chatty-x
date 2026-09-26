# Chatty-X

Личный сервис переписки в выбранных Telegram-чатах. Сейчас доступен demo-режим
с тестовыми собеседниками. Он позволяет проверить интерфейс, объединение серии
сообщений и ручное управление без Telegram и платных моделей. Реальное подключение
Telegram и качество AI ещё проходят разработку и приёмку; прогресс — в `PLAN.md`.

## Локальный запуск Spring Boot из IntelliJ IDEA

Требования: Docker Desktop с Linux-контейнерами, JDK 25, Node.js 22.23.1 или новее
в ветке 22. Импортируйте `backend/pom.xml` как Maven-проект. Maven Wrapper пока
не добавлен; IDE может использовать встроенный Maven.

Из корня `C:\Users\des\IdeaProjects\chatty-x` выполните:

```text
node infra/init-env.mjs --demo
docker compose -f compose.local.yaml up -d --wait db
```

Первая команда создаёт `.env` со случайными секретами, если файла ещё нет.
Существующие значения не меняются. Локальная база использует отдельный том
`chatty-x-local_local-postgres` и доступна только по `127.0.0.1:55432`.

В репозитории есть конфигурация **Chatty-X local** (`.run/Chatty-X-local.run.xml`).
После импорта Maven-проекта выберите её в IntelliJ. При создании конфигурации
вручную для `app.chattyx.ChattyApplication` установите:

| Поле | Значение |
|---|---|
| JRE / Project SDK | JDK 25 |
| Use classpath of module | Maven-модуль `chatty-x` из `backend/pom.xml` |
| Working directory | `C:\Users\des\IdeaProjects\chatty-x` |
| Active profiles | `local` |
| VM options (Windows) | `-Duser.timezone=UTC` |

В обычной конфигурации Application вместо поля Active profiles добавьте
`--spring.profiles.active=local` в Program arguments. Удалите прежние переопределения
`spring.datasource.*` / `SPRING_DATASOURCE_*`, если они направляют приложение к
другой базе. Профиль читает `.env` из рабочего каталога корня и использует
`DB_PASSWORD`. Запуск из `backend/` не поддержан этим профилем: задайте рабочий
каталог явно. Отсутствие `.env` останавливает запуск с понятной ошибкой файла.
Секреты в Run Configuration копировать не нужно.
Импорт файла с подсказкой `.properties` использует стандартный механизм
[Spring Boot Config Data](https://docs.spring.io/spring-boot/reference/features/external-config.html).

После успешного запуска `http://localhost:8080/health` возвращает HTTP 200.
Для интерфейса откройте второй терминал:

```text
cd frontend
npm ci
npm run dev
```

Откройте адрес Vite из терминала, обычно `http://localhost:5173`. Запросы `/api`
и `/health` проксируются в Spring Boot на порту 8080. Для первого входа откройте
локальный `.env`, возьмите `BOOTSTRAP_TOKEN` и настройте владельца, пароль и TOTP.
Профиль `local` всегда использует demo-режим и разрешает cookie по HTTP только
для разработки. Данные и авторизация этого стенда отделены от основного Compose.

Ошибка PostgreSQL `28P01` означает отказ в проверке учётных данных. Без профиля
`local` прямой запуск использует `localhost:5432` и `DATABASE_PASSWORD`; корневой
`.env` сам по себе не загружается. Основной Compose преобразует `DB_PASSWORD`
в `DATABASE_PASSWORD` внутри контейнера. Не удаляйте тома и не сбрасывайте пароль
другого PostgreSQL, чтобы исправить локальный запуск. Если `.env` был изменён после
создания базы, сначала проверьте согласованность пароля с существующим томом.

## Запуск demo целиком в Docker

Из корня проекта при `.env`, созданном с `--demo`:

```text
docker compose up -d --build
```

Откройте `http://localhost:8088`. Этот вариант запускает отдельные backend,
frontend и PostgreSQL; JDK и Maven на Windows не нужны. Не смешивайте его данные
с локальной базой IDE. Изменение кода требует пересборки контейнеров.

## Проверки

Из корня проекта:

```text
docker compose config --quiet
docker compose --profile test run --rm backend-check
docker compose --profile test run --rm frontend-check
```

Backend: Maven verify, JUnit, ArchUnit, WireMock и настоящий PostgreSQL в
Testcontainers. Frontend: установка по lockfile, lint, типы, Vitest и сборка.
Для Playwright каждый раз пересоздавайте **только** одноразовые e2e-сервисы:

```text
docker compose --profile test rm -sf db-e2e backend-e2e web-e2e
docker compose --profile test run --build --rm e2e
```

E2E-база хранится в tmpfs, содержит только синтетические данные и не публикуется
на хост. Обычный набор проверок не подключается к реальным аккаунтам или платным
API. Результаты и ограничения проверок фиксируются в `docs/validation.md`.

## Проверка TDLib/JNI

Для проверки TDLib/JNI отдельно от приложения выполните из корня:

```text
docker compose -f compose.tdlib-test.yaml run --build --rm tdlib-check
```

Ожидается `PASS: JNI load, synchronous execute, asynchronous send/receive, client close`.
Сборке нужен интернет; проверка работает без сети, ключей, аккаунта и пользовательских
томов. Она подтверждает нативную загрузку, получение версии и закрытие клиента.
Авторизация и реальные переписки проверяются отдельно.

## Тестовый вход в Telegram

Отдельный стенд на `http://localhost:8089` использует production TDLib,
собственные тома и отключённые обработчики автоответов. Подготовка ключей,
команды запуска и ручные сценарии описаны в
[docs/telegram-authorization.md](docs/telegram-authorization.md).
Владелец подтвердил реальный вход 2026-09-25; обычные тесты обходятся без аккаунта.
Следующая ручная проверка — [импорт истории одного тестового чата](docs/telegram-history.md).
Там же указана команда обновления стенда с сохранением данных и Telegram-сессии.
На этом стенде по умолчанию отключена и ручная отправка: сообщения не доставляются
при `WORKERS_ENABLED=false`. Порядок проверки очереди, включения обработчиков и
отдельной настройки автоответов — [docs/telegram-sending.md](docs/telegram-sending.md).

## Структура

`backend/` — Java/Spring и миграции Flyway; `frontend/` — React/TypeScript;
`infra/` — контейнеры и эксплуатация; `docs/` — свидетельства проверок.
`AGENTS.md` содержит постоянные правила разработки, `PLANS.md` — правила ExecPlan,
`PLAN.md` — спецификацию, решения и текущий прогресс.
