# Авторизация

`auth-service` использует Spring Authorization Server 1.5.3 (версия управляется Spring Boot 3.5.7), OAuth2 и OpenID Connect. Собственного протокола выдачи токенов нет.

## Как проходит запрос

1. Клиент получает access token через gateway → auth.
2. Клиент отправляет `Authorization: Bearer <access_token>` в gateway → API.
3. API проверяет RS256-подпись, issuer, audience `plot-twist-api`, время действия и scope `api.read`.
4. При вызове domain API передаёт тот же bearer token и request ID. Domain независимо проверяет подпись, issuer, audience `plot-twist-domain`, время действия и scope.

JWKS загружается backend по внутреннему адресу auth и кэшируется JWT-декодером. На каждый запрос к auth обращаться не нужно. Холодному экземпляру или при появлении нового ключа понадобится доступный JWKS; при отсутствии доверенного ключа доступ не разрешается. Приватный RSA-ключ никогда не публикуется.

Access token действует 5 минут. Валидатор допускает стандартный clock skew Spring Security (60 секунд); часы узлов должны быть синхронизированы. Отзыв токена в auth не отменяет уже выданный JWT в resource servers мгновенно: он действует до истечения срока с учётом clock skew. Для немедленного отзыва нужна отдельная стратегия (например, introspection/denylist).

## Локальные OAuth-клиенты

| Клиент | Grant | Использование |
| --- | --- | --- |
| `plot-twist-cli` | Client Credentials | Машинные вызовы и smoke-тесты, scope `api.read` |
| `plot-twist-web` | Authorization Code + PKCE S256, OIDC | Пользовательский вход, scopes `openid api.read`, без client secret |

Машинный grant не является входом пользователя. Его subject — ID клиента. Не передавайте client secret в браузер. Пользовательский subject — имя вошедшего пользователя; `/api/me` позволяет проверить его после входа.

У публичного клиента refresh tokens не выдаются — стандартное безопасное поведение Spring Authorization Server. Для обновления доступа он повторяет code flow с действующей login-сессией. Если понадобится серверный BFF с refresh tokens, зарегистрируйте отдельного confidential-клиента и храните его секрет и refresh token только на сервере.

### Пользовательский вход

Пока предусмотрен bootstrap-пользователь, а не публичная регистрация:

- Локальный username: `demo`.
- Локальный пароль: `local-demo-password`.
- Переопределение: `AUTH_BOOTSTRAP_USERNAME` / `AUTH_BOOTSTRAP_PASSWORD`.

Пароль хранится в БД как BCrypt-хеш. Пользователь создаётся только при отсутствии такого username: смена bootstrap-пароля в `.env` не меняет пароль существующего пользователя. Секрет машинного клиента синхронизируется при старте auth; после его смены перезапустите auth. Локальный default client secret: `local-client-secret`.

Будущий frontend должен использовать OAuth2/OIDC-клиентскую библиотеку: генерировать verifier/challenge S256, проверять `state` (и `nonce` при OIDC), перенаправлять браузер на `/oauth2/authorize`, затем обменивать одноразовый code на `/oauth2/token`. Форму `/login` нельзя использовать как нестандартный password grant. CSRF-защита формы включена. Default callback `http://127.0.0.1:18080/callback` — адрес будущего локального клиента, сервер callback в проекте пока не реализован. Точный разрешённый адрес задаёт `AUTH_REDIRECT_URI`; wildcard redirect URI не используются.

Пользовательский flow без frontend проверяет `python3 scripts/test-auth.py`: cookies, CSRF, login, PKCE, одноразовый code, `/api/me`, вызов domain, отказ без scope и с поддельным JWT. Скрипт перехватывает callback redirect и не требует сервера на порту 18080. Пароли и токены он не печатает. `python3 scripts/test-auth.py --restart` дополнительно останавливает и снова запускает auth в локальной среде: проверяет работу уже прогретых JWT-декодеров во время остановки, сохранность ключа и работоспособность токена после рестарта. Этот вариант временно прерывает новые входы, используйте только в выделенной среде разработки.

### Машинный вызов

Пример для локальной среды (команда выводит JSON с токеном в ваш терминал):

```sh
curl --fail --user 'plot-twist-cli:local-client-secret' \
  --data 'grant_type=client_credentials&scope=api.read' \
  http://localhost:8080/oauth2/token
```

Для вызова API передайте полученный `access_token` в заголовке `Authorization: Bearer …`. Не помещайте токен в URL. Python-проверки получают его автоматически и не выводят:

```sh
python3 scripts/smoke-test.py
python3 scripts/test-auth.py
```

При своих настройках экспортируйте `AUTH_CLIENT_SECRET`, `AUTH_BOOTSTRAP_USERNAME`, `AUTH_BOOTSTRAP_PASSWORD`, `AUTH_REDIRECT_URI`, `GATEWAY_URL` в окружение проверок. Compose читает `.env`, Python — нет.

## Хранение и обновление

- База `auth`: JDBC users, OAuth-клиенты, authorizations и consent. Схема версионируется Flyway; начальная OAuth-схема адаптирована из официальных SQL Spring Authorization Server под PostgreSQL TEXT/TIMESTAMPTZ.
- Том `auth-keys`: PKCS#8 RSA-ключ, создаётся один раз с файловыми правами 0600. Повреждённый ключ останавливает startup, а не заменяется незаметно. Сохраняйте его вместе с резервными копиями, не коммитьте и не удаляйте том.
- `postgres-init` безопасно добавляет отсутствующую базу к существующему PostgreSQL без удаления старых данных. Повторное выполнение не пересоздаёт существующие базы.
- Публичный issuer задаёт `AUTH_ISSUER` (по умолчанию `http://localhost:8080`). Все resource servers должны использовать точно такой же issuer. JWKS в Compose доступен через `http://auth-service:8080/oauth2/jwks`. При изменении gateway-порта обновите issuer.
- Gateway передаёт auth доверенные forwarding headers с исходным host/port. Auth не публикует порт хоста; нельзя допускать обход gateway из недоверенных сетей.

## Границы текущей версии

Есть вход, machine grant, JWT и scope-проверки. Нет публичной регистрации, восстановления пароля, MFA, административного UI и объектных прав на истории/персонажей. `api.read` — начальная крупная граница доступа, а не готовая бизнес-модель разрешений.

Для production нужны HTTPS, Secure cookies, реальные секреты, отдельные пользователи БД, резервные копии и управляемая ротация ключей с периодом перекрытия JWKS. Auth пока один: login-сессии хранятся в памяти и теряются при рестарте, но пользователи/authorizations и signing key сохраняются. Для нескольких auth-экземпляров потребуется общее хранилище сессий (например Spring Session + Redis), заранее подготовленные общие ключи и согласованное управление клиентами. Простое масштабирование auth здесь не заявлено.
