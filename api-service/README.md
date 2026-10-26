# API service

Публичный stateless API за Nginx: проверяет JWT/scopes и передаёт запросы профиля domain-service с пользовательским токеном и разрешёнными заголовками. Собственной БД нет.

```text
io.plottwist.api
├── ApiApplication
├── profile
│   ├── api/ProfileGateway
│   ├── application/port/ProfileBackend
│   └── infrastructure/http/ProfileClient
├── diagnostics
├── config
└── shared/web
```

`ProfileGateway` содержит маршруты, ограничение входного тела и извлечение HTTP-данных. `ProfileClient` отвечает за сетевой вызов, таймауты и разрешённые заголовки ответа. Порт `ProfileBackend` отделяет контроллер от реализации HTTP-клиента. Это HTTP-прокси, поэтому порт сохраняет транспортные типы и статусы без дополнительной бизнес-модели.

`diagnostics` содержит совместимые `/api/story`, `/api/character`, `/api/me`; `config` — resource-server security; `shared/web` — ошибки и bulkhead загрузки фото. Диагностические маршруты не являются новой предметной областью.

Из корня: `./mvnw -pl api-service -am verify`. Docker Compose запускает две реплики; проверки JWT, маршрутов и границ пакетов входят в тесты модуля.

[Общие правила структуры](../docs/backend-structure.md) · [Сквозная архитектура](../docs/architecture.md)
