# Plot Twist — private profile API

Backend API: регистрация, подтверждение email, OAuth2/OIDC, личные профили, истории с картинками/GIF/видео, оценки и комментарии. Angular-фронтенд находится в отдельном репозитории `plot-twist-web`; имя и аватар автора истории доступны вошедшим читателям.

```text
                         ┌─ auth-service ─ PostgreSQL/auth
Client → gateway/Nginx ──┤                  users, sessions, codes, email outbox
                         └─ api-service ×2 → domain-service ─ PostgreSQL/profiles
                                                   └───── private S3 objects
```

Java 25 LTS, Spring Boot 3.5.16, Spring Authorization Server, JDBC/Flyway, Argon2id, virtual threads, AWS SDK v2, OpenAPI 3.1, Micrometer/Prometheus и OpenTelemetry. API — stateless; login-сессии auth хранятся в PostgreSQL. Сервис API не имеет собственной базы данных.

## Запуск локально

Нужны Docker и Docker Compose ≥ 2.24.4, для CLI и проверок — Python 3 без сторонних пакетов.

```sh
docker compose up -d --build --wait --wait-timeout 600
python3 scripts/smoke-test.py
python3 scripts/test-auth.py
python3 scripts/test-profile.py
```

Публичный вход: `http://localhost:8080`. Контракт: `docs/openapi.json` и HTTP `/openapi.json`. Приложение не публикует порты PostgreSQL, auth, domain и S3. `postgres-init`, `garage-init` и `model-download` должны завершиться с кодом 0; остальные контейнеры — быть healthy.

**Локальные письма попадают в Mailpit на `http://localhost:8025`, а не во внешний почтовый ящик.** Локальное S3-хранилище — Garage с сохраняемым томом. Production использует реальный SMTP и внешний приватный S3 bucket; Mailpit/Garage в production-композиции выключены.

### Windows: ошибка `postgres-init`, exit 2

Сначала посмотрите причину: `docker compose logs --no-color postgres-init`. Если лог содержит `set: Illegal option`, `\r` или `$'\r': command not found`, shell-скрипт попал в Linux-контейнер с Windows-окончаниями строк CRLF. `.gitattributes` закрепляет LF для файлов проекта и CRLF для Windows-лаунчеров, независимо от `core.autocrlf`. Дополнительно образы инициализации PostgreSQL и Garage нормализуют свои скрипты при сборке. `postgres-init` использует файлы из образа, без монтирования скриптов из Windows.

Обновите код и пересоздайте только контейнеры инициализации, затем запустите приложение. Команды подходят для PowerShell:

```powershell
git pull
docker compose build --no-cache postgres-init garage
docker compose rm -f postgres-init garage-init
docker compose up -d --build --wait --wait-timeout 600
```

Пересборка нужна в том числе для `garage-init`: он использует образ `garage`. Ручное преобразование файлов не требуется. Тома с базой, ключами и фотографиями сохраняются; удалять их для исправления окончаний строк не требуется. Если лог сообщает другую ошибку, диагностируйте её по логу, а не по одному коду завершения.

## Зарегистрироваться и пользоваться API

### Браузерный интерфейс Angular

Фронтенд находится в отдельном проекте `plot-twist-web`. После обновления backend выполните `docker compose up -d --build --wait --wait-timeout 600`, чтобы auth-service зарегистрировал браузерный клиент `plot-twist-browser`. В папке фронтенда: `npm ci` и `npm start`, затем откройте **http://localhost:4200**. Альтернатива без Node на хосте: `docker compose up -d --build --wait` из папки фронтенда; backend должен быть уже запущен и иметь сеть `plot-twist_edge`.

Для браузера используется отдельный Authorization Code + PKCE клиент с callback `http://localhost:4200/auth/callback`, для CLI сохраняется прежний `plot-twist-web` и loopback callback. В локальном HTTP-режиме регистрируются два точных callback: для `localhost` и `127.0.0.1`, с теми же портом и путём. Параметр `AUTH_BROWSER_REDIRECT_URI` меняет браузерный callback; адрес завершения logout — тот же origin и `/signed-out`. В production нужен точный HTTPS callback, без автоматических алиасов. Frontend proxy отправляет API и auth-пути на gateway, поэтому CORS не требуется. Фронтенд может отдавать собственные стили `/default-ui.css` для формы auth-service.

### CLI

```sh
python3 scripts/client.py register me@example.com
# Возьмите одноразовый код из письма в Mailpit:
python3 scripts/client.py verify
python3 scripts/client.py login
```

`login` открывает стандартную форму auth в браузере, выполняет Authorization Code + PKCE и принимает callback на loopback-порту 18080. Это native OAuth-клиент, отдельный frontend не нужен. Запускайте CLI на компьютере с вашим браузером. Пароль не сохраняется; access token хранится в `.credentials/session.json` с правами 0600. Для удалённого API передайте `--url https://ваш-домен` **до** команды.

Создайте `.credentials/profile.json`:

```json
{
  "displayName": "Alex",
  "givenName": "Alex",
  "familyName": null,
  "bio": "About me",
  "birthDate": "1990-01-02"
}
```

```sh
python3 scripts/client.py profile-put .credentials/profile.json
python3 scripts/client.py profile
python3 scripts/client.py photo-put /path/to/photo.png
python3 scripts/client.py photo-get .credentials/avatar.jpg
python3 scripts/client.py photo-delete
python3 scripts/client.py delete-profile
```

Для прямых HTTP-запросов: `TOKEN=$(python3 scripts/client.py token)` и заголовок `Authorization: Bearer $TOKEN`. Не помещайте токен в URL, логи или Git. Запись профиля требует `If-Match` с текущим ETag; первоначальное создание — `If-None-Match: *`. CLI делает это автоматически. Без версионного условия — 428, при конфликте — 412. После истечения пятиминутного access token повторите `login`; действующая браузерная login-сессия позволяет не вводить пароль заново.

Профиль появляется при первом PUT, не при регистрации. UUID владельца берётся исключительно из проверенного JWT. Машинный grant не получает доступа к личным профилям. Пользователь может прочитать и изменить только свой профиль; API не принимает владельца в теле запроса.

Фото: JPEG/PNG, до 5 MiB и 16 MP. Сервер проверяет формат, ограничивает декодирование, удаляет исходные метаданные и преобразует изображение в JPEG до 1024 px. В PostgreSQL хранятся метаданные и связь с владельцем, байты — в S3. Публичных URL bucket нет; скачивание требует JWT. Старые и неудачные объекты удаляются отложенным сборщиком.

## Доступ и восстановление

```sh
python3 scripts/client.py resend me@example.com
python3 scripts/client.py forgot me@example.com
# Код из нового письма:
python3 scripts/client.py reset
python3 scripts/client.py logout
```

Подтверждение и reset используют одноразовые коды на 30 минут. В БД хранится хеш кода; письмо отправляется после коммита через transactional outbox. Повторная отправка ограничена, ответы для неизвестных/существующих email одинаковые. Reset удаляет login-сессии и issuer-side authorizations. Уже выпущенный JWT может оставаться действительным до окончания TTL с учётом clock skew. `logout` CLI удаляет локальный токен; это не немедленный глобальный отзыв JWT.

## Проверки и эксплуатация

Grafana запускается вместе с локальным Compose: **http://localhost:3000/d/plot-twist-overview**. Вход при первой инициализации: **admin / plot-twist-local**; его можно настроить через `GRAFANA_ADMIN_PASSWORD`. Дашборд и Prometheus datasource устанавливаются автоматически. Prometheus: **http://localhost:9090/targets**. После запуска подождите 30–60 секунд и выполните запросы через приложение, чтобы увидеть HTTP RPS и p95/p99. [Инструкция по графикам, хранению и доступу](docs/monitoring.md).

```sh
python3 scripts/test-auth.py --restart    # временно останавливает auth, только в dev
python3 scripts/test-resilience.py      # масштабирует/останавливает сервисы, только в dev
python3 scripts/test-backup-restore.py  # восстанавливает копии в новые временные БД
python3 scripts/test-profile.py --benchmark
python3 scripts/test-monitoring.py     # provisioning, targets и реальные RPS/p95
```

Для небольшого read-теста вашего существующего профиля после CLI login:

```sh
python3 scripts/benchmark.py --rps 20 --seconds 15
```

Это проверка одного ограниченного сценария, а не измерение предельной нагрузки. Выбирайте целевые RPS, p95/p99 и бюджет ошибок до capacity-тестов. Prometheus собирает приватные `/actuator/prometheus` каждой реплики через DNS discovery из `monitoring/prometheus.yml`; HTTP-гистограммы включены общей Compose-конфигурацией `monitoring/java-metrics.env`. Для экспорта трасс включите `TRACING_ENABLED` и задайте OTLP endpoint своего collector. Логи приложений — ECS JSON без тел запросов и паролей; локальные Docker-логи ограничены по объёму.

`/healthz` проверяет Nginx; `/readyz` — выбранный API. Готовность domain учитывает его БД; S3 имеет отдельный health component, поэтому сбой фото-хранилища не отключает редактирование текстовых полей профиля. Сквозной smoke проверяет зависимости отдельно.

```sh
docker compose up -d --no-build --scale api-service=3 --wait
EXPECTED_API_INSTANCES=3 python3 scripts/smoke-test.py
docker compose down  # данные сохраняются; не добавляйте -v, если они нужны
```

## Production и архитектура

- [Структура backend, границы пакетов и правила разработки](docs/backend-structure.md).
- Модули: [auth-service](auth-service/README.md), [api-service](api-service/README.md), [domain-service](domain-service/README.md).
- [Production-развёртывание, секреты, TLS, резервные копии](docs/production.md).
- [Авторизация и её границы](docs/auth.md).
- [Решения по архитектуре и Kafka/RabbitMQ](docs/architecture.md).
- [Результаты аудита](docs/audit.md).

Production-файл — `compose.prod.yaml`: TLS через Caddy, закрытый gateway, внешние SMTP/S3, отключённые demo-переключатели, секреты из файлов и проверки конфигурации при старте. Это вариант для одного Linux-хоста, **не** отказоустойчивый кластер. Публичное развёртывание требует выбранной площадки, DNS, реальных секретов и проверенных backup/restore и мониторинга; само добавление YAML этого не выполняет.

CI строит образы с Java-тестами и выполняет контрактные, интеграционные и restore-проверки. Образы базовых runtime закреплены digest, обновления зависимостей предлагаются Dependabot.

## Лента историй

Angular-фронт `plot-twist-web` теперь открывает `/feed`: чтение и публикация историй, редактирование/удаление своих, одна оценка на пользователя и комментарии. Доступ — после входа; проверка авторства и уникальность оценки обеспечиваются сервером/PostgreSQL. Новая миграция добавляется без удаления данных. [Маршруты, правила и запуск](docs/stories.md).

```sh
python3 scripts/test-stories.py
```

В ленте также доступны аватары авторов и вкладка «Понравившиеся». Свои комментарии редактируются с проверкой версии. Flyway V3 обновляет существующую схему без удаления данных; [подробности](docs/stories.md).

## Смысловые рекомендации

В Angular появилась вкладка **Для вас**. Локальная многоязычная E5/ONNX создаёт embeddings в фоне, Qdrant ищет похожие истории, PostgreSQL проверяет актуальность, оценки и права. Индексация использует транзакционную очередь с retry/lease, выдача — приватные снимки с курсорами. При недоступной модели включается явно обозначенный fallback. [Архитектура, ресурсы, загрузка модели и проверки](docs/recommendations.md).

Первый полный запуск с загрузкой модели: `docker compose up -d --build --wait --wait-timeout 600`. Нужен доступ к Hugging Face; без реальных весов семантическая проверка не считается пройденной. Данные существующих сервисов сохраняются.


Модерация историй выполняется отдельным `moderation-service` через RabbitMQ и transactional outbox. Создание/правка возвращают 202; статусы видны автору в Angular. Чужим читателям и Qdrant доступен только одобренный снимок. [Архитектура, правила, эксплуатация и изолированные проверки](docs/moderation.md).

## Медиа в историях

Редактор Angular поддерживает загрузку, перетаскивание и вставку файлов в текст: JPEG/PNG до 10 MiB, GIF до 15 MiB, MP4/WebM до 50 MiB и двух минут, до 8 вложений. Технически проверенные файлы хранятся в приватном S3; публикация следует после проверки текста через RabbitMQ. [API, ограничения, обновление и изолированные тесты](docs/story-media.md). Для production добавлен обязательный секрет `story-media-signing-key`.
