# Миграция пользовательской аутентификации на Firebase Authentication

## Текущий поток аутентификации

Публичные `POST /api/v1/auth/register` и `POST /api/v1/auth/login` принимают email и
пароль. `UserApplicationService` нормализует email, хеширует пароль
Argon2id и создаёт `users.id` как UUID. `password_hash` обязателен в миграции
V1, `UsersTable`, `UserRecord` и JDBC-mapping.

При успешной регистрации/входе сервер:

1. Выдаёт HS256 access JWT. `sub` содержит внутренний `users.id`, также
   записываются `iss`, `aud`, `iat` и `exp`.
2. Выдаёт 32-байтный random refresh token в hex-виде. В `refresh_tokens`
   хранится только SHA-256 hash, срок действия, время отзыва и ссылка на
   replacement token.
3. `POST /api/v1/auth/refresh` в одной JDBC-транзакции блокирует текущую
   сессию, создаёт replacement и отзывает старую. Повторное использование
   старого token отклоняется.
4. Защищённый `POST /api/v1/auth/logout` отзывает переданный refresh token
   только для текущего user UUID. Access JWT при этом не отзывается и живёт до
   `exp`.

Ktor provider `user-jwt` проверяет HMAC-подпись, issuer и audience и создаёт
`JWTPrincipal`. `ApplicationCall.userId()` парсит UUID из `principal.subject`.
Все пользовательские routes размещены в `authenticate(USER_JWT_AUTH)`; исключение —
публичные register/login/refresh.

Активный production path для users — `UserApplicationService` +
`JdbcUserApplicationStore`. Отдельные `UserRepository`, `RefreshTokenRepository` и их
Exposed-реализации существуют, но не подключены в `Application.kt` к текущему
auth flow.

## Авторизация по внутреннему UUID

Бизнес-логика не зависит от формата JWT: routes передают в сервисы
уже извлечённый `userId: UUID`.

- Plants выбираются/изменяются по `plants.id` и `plants.user_id`.
- Devices проверяются через принадлежащий user plant. Перемещение
  device также проверяет владение target plant.
- Latest/history/SSE проверяют `plants.user_id`; history сначала вызывает
  `ownsPlant`.
- Alert rules/events вызывают `requirePlant`, который сопоставляет
  `plant.id + user_id`.
- Чужие или archived resources возвращают `404 NOT_FOUND`, а не `403`, чтобы не
  раскрывать их существование.

Эта граница позволяет заменить principal, не переписывая ownership-логику.

## Device Token ESP32

Device-аутентификация отделена от user auth:

- provider называется `device-token` и принимает только `Authorization: Device <token>`;
- `ExposedDeviceTokenAuthenticator` хеширует candidate через SHA-256 и
  сравнивает с `devices.token_hash` через `MessageDigest.isEqual`;
- principal содержит `deviceId: UUID` и disabled-state;
- приём single/batch telemetry дополнительно загружает device по UUID и
  отклоняет disabled device;
- raw token в базе не хранится. Он выводится operator-у только при
  provisioning/rotation.

Этот provider, `DevicePrincipal`, хеши, схема ESP32 и telemetry routes при
переходе на Firebase не меняются.

## Схема и миграции

- `V1__initial_schema.sql` создаёт users, plants, devices, measurements,
  device latest state, refresh tokens, alerts и notification outbox.
- `V2__device_claim_codes.sql` добавляет одноразовые коды привязки device.
- `V3__alert_processing_state.sql` добавляет состояние alert engine, idempotency key
  outbox и partial index для active devices.

Сервер запускает Flyway до подъёма HTTP. Применённые V1–V3 нельзя менять;
поддержка Firebase должна появиться в новой numbered migration.

## Конфигурация

Сейчас user auth требует `JWT_ISSUER`, `JWT_AUDIENCE`, `JWT_SECRET`; сроки
access/refresh задаются опциональными `JWT_ACCESS_TOKEN_SECONDS` и
`JWT_REFRESH_TOKEN_SECONDS`. Прочая конфигурация не связана с user auth:
`HTTP_PORT`, database credentials, `ALLOWED_ORIGINS`, telemetry/history/alert/outbox controls.

В будущем Firebase-потоке JWT-переменные потеряют смысл после полного
отключения legacy auth. Их нужно заменить на `FIREBASE_PROJECT_ID`, Application
Default Credentials / `GOOGLE_APPLICATION_CREDENTIALS`, `FIREBASE_AUTO_PROVISION_USERS` и
`FIREBASE_CHECK_REVOKED_TOKENS`. На шаге 1 конфигурация не изменяется.

## Исходное состояние пользователей

В проекте никогда не было реальных пользователей. Таблица `users` не содержит
аккаунтов, которые нужно переносить или связывать с Firebase вручную. Записи,
создаваемые unit- и integration-тестами, являются только одноразовыми фикстурами.

## Целевое сопоставление Firebase UID и internal UUID

Целевая модель — отдельная таблица `user_identities`, а не Firebase UID в
`users.id`:

```text
(provider = "firebase", external_subject = Firebase UID) -> user_identities.user_id -> users.id UUID
```

Пара `(provider, external_subject)` должна быть unique, как и `(user_id, provider)`.
После проверки Firebase ID Token provider ищет identity по UID и создаёт
principal с найденным internal UUID. Именно этот UUID передаётся в
существующую business/ownership logic.

Стратегия rollout:

1. По умолчанию `FIREBASE_AUTO_PROVISION_USERS=true`.
2. Первый валидный Firebase UID атомарно создаёт `users` и `user_identities`;
   unique constraints делают повторные и конкурентные первые входы idempotent.
3. `password_hash` и `email` nullable для Firebase-only users, поэтому токен без
   email поддерживается без создания вымышленного адреса.
4. Флаг можно установить в `false`, чтобы временно запрещать создание новых
   внутренних аккаунтов; неизвестный UID тогда получает отказ.

## Компоненты миграции

| Заменить/отключить | Сохранить |
| --- | --- |
| Register/login и `PasswordHasher` | `users.id UUID` и все внешние ключи |
| Выдачу и HS256-проверку user JWT | Plants/devices/telemetry/alerts business logic |
| Refresh/logout flow и активное использование `refresh_tokens` | Ownership checks и 404-hiding |
| `JWTPrincipal` и `ApplicationCall.userId()` | `ApiErrorResponse`, request ID, CORS, health checks |
| JWT config и auth DTO после compatibility window | PostgreSQL, Flyway, Hikari, repository boundaries |
| | Provider `device-token`, `DevicePrincipal`, device token hashes и ESP32 API |

Физическое удаление `password_hash`, `refresh_tokens`, legacy DTO/кода и старых
миграций в текущий шаг не входит: по плану задачи оно выполняется после перевода
маршрутов на Firebase.

## Тестовое покрытие и пробелы

Сейчас проверяются:

- Argon2id password hashing, email normalization, refresh rotation/replay;
- Ktor JWT guard и доступ к plant с выданным access token;
- plant/device ownership hiding;
- telemetry history и SSE ownership;
- device-token success, invalid token и disabled device;
- repeatable Flyway migration, таблицы, FK restrict, measurement idempotency;
- alert engine, notification outbox и telemetry validation/ingestion.

Для Firebase пока нет verifier/principal tests, identity mapping, auto-provisioning,
concurrent first-login и regression-тестов после замены provider. Они добавляются на
последующих шагах с fake Firebase verifier и PostgreSQL Testcontainers, без вызова
реального Firebase.

## Статус реализации: шаг 2

Добавлены Firebase Admin SDK и граница `FirebaseTokenVerifier`. Production verifier
вызывает `FirebaseAuth.verifyIdToken(idToken, checkRevoked)` на `Dispatchers.IO` и
возвращает только UID, optional email и `emailVerified`. Для тестов существует fake,
не обращающийся к Firebase.

Именованный Firebase app `jaiqal-auth` и verifier создаются thread-safe initializer-ом
не более одного раза при старте процесса. SDK использует Application Default
Credentials; `FIREBASE_PROJECT_ID` обязателен, а
`FIREBASE_CHECK_REVOKED_TOKENS=false` является значением по умолчанию. Отсутствие
project ID или ADC завершает запуск понятной ошибкой без вывода credential contents.

На этом шаге verifier намеренно не подключён к Ktor user provider. Legacy `user-jwt`,
маршруты и выдача собственных токенов остаются активными до шагов 4–6; Device Token
не изменён.

## Статус реализации: шаг 3

Миграция `V4__firebase_user_identities.sql` добавляет отдельную таблицу
`user_identities`. Уникальные ограничения на `(provider, external_subject)` и
`(user_id, provider)` гарантируют не более одной внутренней учётной записи на
Firebase UID и не более одной Firebase identity на internal UUID. `users.id`, все
его внешние ключи, legacy `password_hash` и `refresh_tokens` сохранены.

Для Firebase-only пользователей `users.password_hash` и `users.email` стали
nullable. Это позволяет создавать пользователя из валидного токена без пароля и
не выдумывать email, если Firebase provider его не вернул. Legacy password login
по-прежнему работает только для записи с непустым password hash.

`FIREBASE_AUTO_PROVISION_USERS=true` — значение по умолчанию, поскольку переносить
существующих пользователей не требуется. Создание `users` и `user_identities`
выполняется в одной транзакции. Конкурентный первый вход с тем же UID возвращает
запись победившей транзакции. При явном выключении auto-provisioning неизвестный
UID получает понятный отказ.
