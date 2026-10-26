# Auth service

Владеет аккаунтами, подтверждением email, восстановлением доступа, OAuth2/OIDC, серверными login-сессиями и outbox писем. База: PostgreSQL `auth`.

```text
io.plottwist.auth
├── AuthApplication
├── account
│   ├── api/{request,validation}
│   ├── application/port
│   ├── model
│   └── infrastructure/persistence
├── notification
│   ├── application/port
│   └── infrastructure/{persistence,mail}
├── oauth/infrastructure/{client,crypto,jdbc,security,web}
├── security/web
├── config
└── shared/web
```

`AccountService` управляет транзакцией аккаунта и outbox через порты. JDBC-адаптеры используют один datasource, поэтому запись кода и намерения отправить письмо атомарна. `EmailDeliveryWorker` отвечает за SMTP, повторы и очистку истёкших записей.

В `config` отдельно находятся security chains, кодирование пароля/JDBC users, OAuth persistence, регистрация клиентов и параметры issuer/JWK. SQL схемы и Spring Session — в `src/main/resources/db/migration`. Модели и password policy не зависят от HTTP. Ключ подписи хранится вне исходников и образа.

Из корня репозитория: `./mvnw -pl auth-service -am verify`. Для общего запуска используйте корневой Docker Compose. Тесты повторяют функциональные пакеты; архитектурные правила — в `src/test/java/io/plottwist/auth/architecture`.

[Общие правила структуры](../docs/backend-structure.md) · [Авторизация](../docs/auth.md)
