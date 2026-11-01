# Authentication and authorization

## Protocol and trust

Spring Authorization Server 1.5.x is managed by the Spring Boot BOM. Human clients use Authorization Code + PKCE; OIDC is available with `openid`. No password grant or custom JWT login protocol is introduced.

`plot-twist-web` is a public first-party native client with an exact callback from `AUTH_REDIRECT_URI`. It can request `api.read`/`api.write`; it has no client secret and receives no refresh token. The public CLI uses a local callback and system browser. New access is obtained through another PKCE flow; the issuer login session has a four-hour idle timeout by default.

The separately deployed Angular frontend uses `plot-twist-browser`, also a public Authorization Code + PKCE client. `AUTH_BROWSER_REDIRECT_URI` registers the exact callback and the same origin's `/signed-out` logout callback. For HTTP loopback development only, both `localhost` and `127.0.0.1` are registered with the configured port/path. A browser-specific validator requires an exact redirect match including the port; Spring's native-client loopback ephemeral-port exception does not apply to this browser client. Native CLI validation stays on the standard path. HTTPS and other hostnames do not receive aliases; production requires HTTPS. The browser access token defaults to four hours (`AUTH_SESSION_DURATION=4h`); machine and native CLI access tokens remain five minutes. The backend reconciles this client on startup, so changing source/configuration requires rebuilding/restarting auth-service. Never reuse an old authorization URL after changing hosts: restart login from the frontend so PKCE state is created in the correct origin's sessionStorage.

The reserved `plot-twist-cli` machine client and `demo` user are enabled only by the development Compose file. Production disables those switches and refuses to start with the reserved machine client or an enabled demo account using its known development password. A new production database does not contain either.

Resource servers independently validate RS256 signature, exact issuer, their own audience, timestamps and scope. Access tokens live for five minutes; Spring's default timestamp skew is 60 seconds. Tokens for verified accounts contain immutable UUID `sub` and matching `account_id`. Personal profile handlers require both; machine tokens cannot impersonate users by supplying an owner/header/body field. API passes the original bearer token to domain, which validates it again.

JWT verification uses cached JWKS, not a remote authorization call on every API request. A cold instance or unknown key needs an available issuer. Revocation/password reset does not instantly invalidate a JWT already accepted by local verification; a stricter revocation requirement would need introspection or a distributed token-version/revocation mechanism.

## Account lifecycle

- `POST /auth/v1/registrations`: normalized email and password; generic 202, no token returned.
- `POST /auth/v1/email-verifications/requests`: resend confirmation, generic 202.
- `POST /auth/v1/email-verifications`: redeem one-time code; enables login.
- `POST /auth/v1/password-resets/requests`: generic 202.
- `POST /auth/v1/password-resets`: redeem code, set password and clear issuer-side sessions/authorizations.

Passwords contain 15–128 Unicode code points and no control characters. New hashes use Argon2id (19 MiB, 2 iterations, parallelism 1); existing BCrypt hashes can still be verified, and reset writes Argon2id. Plaintext passwords are neither persisted nor logged. Password DTO string representations are redacted.

Codes have 256 bits of randomness; only SHA-256 digests are stored in the token table. They expire in 30 minutes and are consumed under a PostgreSQL row lock. Notification content exists transiently in the restricted email outbox and is removed after delivery/expiry. Verification cannot be replayed. A password reset can also recover an unverified registration by proving email ownership.

Email uniqueness is case-insensitive through normalization and database uniqueness, including races. Duplicate registration does not replace the existing password. Per-account resend cooldown is 60 seconds. Five failed password attempts in a five-minute window temporarily lock the account; reset clears the lock. Unknown account errors and login redirects do not expose detailed account status. Gateway rate limits and an eight-slot cryptographic-work bulkhead provide additional backpressure.

## Authorization-code concurrency

A test of the original implementation issued four successful responses for four simultaneous exchanges of one code. The token endpoint now has a DB transaction and locks the authorization-code row before PKCE/code validation. A buffered HTTP response is released only after commit; failures cannot return an uncommitted token. The regression check requires exactly one successful exchange among four concurrent requests.

Replaying a consumed code may invalidate its authorization at the issuer, as required by the standard mechanism. Locally verified access JWTs still have the bounded revocation window described above. Do not automatically retry an ambiguous code exchange; start a fresh PKCE flow.

## Persistent state and operations

JDBC stores users, accounts, authorizations, consent and login sessions in database `auth`, under a non-superuser account separate from domain. Flyway manages the schemas. Equality token lookups use PostgreSQL hash indexes, avoiding the B-tree entry-size limit for JWTs. Expired authorizations are cleaned in bounded batches.

Development creates a persistent RSA signing key in `auth-keys`; production requires an externally provisioned PKCS#8 key and never silently regenerates it. Key rotation must retain previous public keys until issued tokens expire: replacing the only key is not a complete rotation strategy. A managed keystore/KMS or a deliberate multi-key JWKS rollout is an infrastructure follow-up.

Login state is in JDBC, but an HA rollout still needs all auth instances to use the same externally managed signing identity, the same client configuration and this code version. No mixed rollout with the old unlocked code-redemption implementation is safe. The supplied Compose production topology defaults to one auth instance; it does not claim a multi-host HA deployment.

Local demo credentials are in `.env.example`. Local mail is caught by Mailpit. Do not point the destructive integration tests at real user data or a production SMTP account.

## Служебные запросы браузера во время входа

После отправки формы Spring продолжает только сохранённый GET `/oauth2/authorize`, включая state и PKCE. Один `AuthorizationRequestCache` используется цепочками authorization server и login. Запросы favicon, DevTools и неизвестных ресурсов не заменяют OAuth-запрос; старые посторонние SavedRequest удаляются при чтении из существующей сессии.

`/.well-known/appspecific/com.chrome.devtools.json` — необязательный запрос Chrome DevTools. Gateway отвечает на `/.well-known/appspecific/` обычным 404 без обращения к auth-service; при прямом обращении к auth-service этот ресурс тоже не требует входа. 404 для отсутствующего файла допустим и не должен становиться адресом перехода после логина. OIDC discovery и OAuth metadata продолжают работать.

## Продолжительность браузерной сессии

По умолчанию `AUTH_SESSION_DURATION=4h` задаёт срок нового browser access token и idle timeout JDBC-сессии issuer. Параметр доступен в локальном и production Compose; допустимы значения от `1m` до `12h`, например `2h`. При успешном вводе учётных данных timeout обновляется и у существующей JDBC-сессии. Изменение настройки требует пересоздания auth-service; уже выданные токены сохраняют прежний срок, поэтому для получения нового срока нужно войти заново.

Angular использует срок `expires_in`, не отдельный четырёхчасовой таймер. При истечении токена, возвращении к просроченной вкладке или актуальном API 401 приложение очищает токены и отметку восстановления, заменяет текущий адрес экраном входа `/` и показывает сообщение об окончании сессии. После этого F5 и прямой переход в приватный раздел не восстанавливают старую сессию автоматически; кнопка входа запускает PKCE с `prompt=login`. После успешного входа флаг истечения снимается. В sessionStorage хранятся только несекретные отметки, токены остаются в памяти.

Idle timeout issuer и срок access token различаются: F5 с действующей серверной сессией выполняет новый PKCE grant и может продлить пользовательский вход. Это не абсолютное ограничение времени от первого ввода пароля. Истечение серверной сессии обнаруживается при следующем обращении к issuer; действующий stateless JWT работает до своего exp. Немедленный глобальный отзыв требует отдельного механизма.

Увеличение browser JWT до четырёх часов также увеличивает окно его действия после logout/reset: серверы ресурсов проверяют JWT локально, поэтому очистка issuer-side authorizations не отзывает уже выданный JWT мгновенно. Для более строгого production-требования нужен BFF с короткими access tokens либо отдельная проверка отзыва. Срок одноразовых email/reset-кодов остаётся 30 минут, machine/native токенов — пять минут.

SAS 1.5 сам по себе не обеспечивает `prompt=login`: `BrowserReauthenticationFilter` обрабатывает его для browser client и точного зарегистрированного redirect URI, сохраняет OAuth-параметры, очищает issuer security context и направляет на форму с CSRF. RequestCache убирает `prompt=login` из сохранённого продолжения, чтобы успешный ввод пароля не создавал цикл повторного входа. Неизвестные redirect URI остаются на штатном OAuth-валидаторе и не завершают сессию.
