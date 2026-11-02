# Profiles, stories, media and private chats

Владеет личными профилями, версиями записей, метаданными фото и жизненным циклом приватных объектов. База: PostgreSQL `profiles`; байты фото — приватное S3-совместимое хранилище.

```text
io.plottwist.domain
├── DomainApplication
├── profile
│   ├── api/{request,response}
│   ├── application/port
│   ├── model
│   └── infrastructure/persistence
├── story/{api,application/port,model,infrastructure/persistence}
├── chat/{api,application/port,model,infrastructure/persistence}
├── media
│   ├── application/port
│   ├── model
│   └── infrastructure/{persistence,s3,image,cleanup}
├── config
├── diagnostics
└── shared/web
```

`ProfileService` управляет сценариями и транзакциями через порты. `JdbcProfileRepository` и `JdbcMediaRepository` содержат SQL, `S3ObjectStorage` — AWS SDK, `ImageSanitizer` — декодирование и безопасную перекодировку изображения. `MediaCleanup` удаляет отложенные и неудачные объекты. Профиль и медиа используют одну БД, сохраняя атомарность изменения ссылок и версий.

Контроллер принимает `UpdateProfileRequest`, переводит его в `ProfileDetails` и возвращает `ProfileResponse`. Модели не зависят от Jakarta Validation, HTTP URL и SDK. Ошибки сценариев переводятся в RFC 9457 в `shared/web`.

Имя Maven-модуля `domain-service` и базовый пакет сохранены для существующих сборок и Compose; `model` обозначает слой чистых моделей. Flyway-схема — `src/main/resources/db/migration`.

Из корня: `./mvnw -pl domain-service -am verify`. Интеграционные проверки профиля/S3 — `python3 scripts/test-profile.py` при запущенном Compose.

[Общие правила структуры](../docs/backend-structure.md) · [Контракт API](../docs/openapi.json)

`StoryService` и `JdbcStoryRepository` реализуют ленту с курсорами, авторство, версионирование, уникальные оценки и комментарии в той же БД. Миграция V2 дополняет V1; данные профилей сохраняются. Проверка: `python3 scripts/test-stories.py`. [Подробности](../docs/stories.md).

`StoryAuthorPhotos` читает только активный JPEG автора существующей истории через общий порт ObjectStorage. Личные поля профиля остаются доступны только владельцу. Комментарии редактируются по собственной версии, `liked=true` выбирает текущие лайки JWT-пользователя. Схема дополняется миграцией V3.

Пакет `chat` отвечает за приватную переписку один на один: участники, последовательности сообщений, идемпотентность, курсорная история и read markers. Данные — в PostgreSQL, Flyway V11; публичная модерация/рекомендации не получают тексты. [Контракт и проверки](../docs/chats.md).
