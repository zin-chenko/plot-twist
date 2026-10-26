# Plot Twist

Многомодульный Maven-проект: Java 21, Spring Boot 3.5.7 и PostgreSQL 17.

| Модуль | Назначение | Порт | Проверочный API | База данных |
| --- | --- | --- | --- | --- |
| `story-service` | Основа сервиса историй | 8081 | `/api/story` | `story` |
| `character-service` | Основа сервиса персонажей | 8082 | `/api/character` | `character` |

Сейчас сервисы содержат стартовые HTTP-эндпоинты и подключение к БД. Предметная логика и таблицы пока не добавлены. Каждый сервис собирается в отдельный JAR и Docker-образ. В одном экземпляре PostgreSQL создаются две отдельные базы; для локальной разработки используется общий пользователь.

## Запуск вместе

Нужны Docker Engine (или Docker Desktop) и Docker Compose v2. Java и Maven на хосте для этого не нужны.

```sh
docker compose up -d --build --wait
docker compose logs -f
```

PostgreSQL хранит данные в именованном томе `postgres-data`. Сервисы запускаются после проверки готовности PostgreSQL. На хосте порты доступны только через `127.0.0.1`.

Проверка готовности каждого сервиса, включая реальное соединение с его БД:

```sh
curl --fail http://localhost:8081/actuator/health/readiness
curl --fail http://localhost:8082/actuator/health/readiness
```

Автоматическая проверка обоих API и готовности БД (нужен Python 3, ожидает запуск до 90 секунд на сервис):

```sh
python3 scripts/smoke-test.py
```

```sh
docker compose down       # остановить, сохранив данные
```

Для полного удаления локальных данных: `docker compose down -v`. Это удалит базы. Скрипт `docker/postgres/init.sql` выполняется только при первом создании тома.

## Настройки

Необязательно скопируйте `.env.example` в `.env` и измените пароль или порты. Значения по умолчанию предназначены только для локальной разработки. `.env` не попадает в Git и Docker build context. Изменение пароля в `.env` не меняет пароль в уже созданной БД: меняйте его средствами PostgreSQL либо пересоздайте том, если данные не нужны.

Переменные сервисов: `DB_URL`, `DB_USER`, `DB_PASSWORD`. В Compose адрес БД — `postgres:5432`, при локальном запуске JVM — `localhost:5432`. Для smoke-теста с нестандартными портами передайте `STORY_PORT` и `CHARACTER_PORT` в окружении процесса.

## Разработка без контейнеров Java

Нужен JDK 21; Maven Wrapper загрузит Maven 3.9.11.

```sh
./mvnw verify
docker compose up -d postgres
./mvnw -pl story-service spring-boot:run -Dspring-boot.run.arguments=--server.port=8081
# В другом терминале:
./mvnw -pl character-service spring-boot:run -Dspring-boot.run.arguments=--server.port=8082
```

На Windows используйте `mvnw.cmd`. При изменённом пароле БД передайте `DB_PASSWORD` обоим JVM-процессам.

В каждом модуле есть HTTP-тест контроллера. `verify` собирает оба исполняемых JAR и запускает тесты; проверка настоящей БД выполняется smoke-тестом после запуска Compose.

Dockerfile поддерживает необязательные BuildKit secrets: `maven_settings` для корпоративного Maven proxy/mirror и `java_cacerts` для доверенного Java truststore. Проверка TLS остаётся включённой. Настройки сети облачной среды не включаются в исходники.
