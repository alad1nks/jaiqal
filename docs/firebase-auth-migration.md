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
не выдумывать email, если Firebase provider его не вернул. Колонка password hash
сохранена в схеме до отдельной cleanup-миграции, но активного password login больше нет.

`FIREBASE_AUTO_PROVISION_USERS=true` — значение по умолчанию, поскольку переносить
существующих пользователей не требуется. Создание `users` и `user_identities`
выполняется в одной транзакции. Конкурентный первый вход с тем же UID возвращает
запись победившей транзакции. При явном выключении auto-provisioning неизвестный
UID получает понятный отказ.

## Статус реализации: шаг 4

Добавлен отдельный Ktor provider `firebase-user`. Он принимает только схему
`Authorization: Bearer`, проверяет ID Token через `FirebaseTokenVerifier`, находит
или атомарно создаёт внутреннего пользователя и формирует `UserPrincipal` с
internal `userId`, Firebase UID, email и признаком его верификации. JDBC lookup и
provisioning выполняются на `Dispatchers.IO`.

Ошибки подписи, issuer/audience/project, срока действия, disabled/revoked user,
отсутствующая или некорректная схема, неизвестный UID при выключенном provisioning
и конфликт identity дают одинаковый нейтральный `401 Unauthorized`; токен и детали
Firebase/БД в ответ не попадают. Неожиданные инфраструктурные ошибки не маскируются
под ошибку аутентификации.

Production wiring использует singleton verifier из Firebase Admin и
`JdbcUserIdentityStore`. Provider `device-token` остаётся отдельным и принимает
только схему `Device`. На момент завершения шага 4 пользовательские маршруты ещё
оставались на `user-jwt`; их текущее состояние после переключения описано ниже.

## Статус реализации: шаг 5

Все существующие защищённые пользовательские маршруты переведены с `user-jwt` на
`firebase-user`: растения, пользовательские устройства, latest/history/SSE
телеметрия, alert rules, история alerts и acknowledgement. Logout также находится
под Firebase provider до отключения всего legacy auth API на шаге 6.

`ApplicationCall.userId()` теперь читает только internal UUID из `UserPrincipal`;
Firebase UID не передаётся в repositories или ownership checks. Благодаря этому
существующие фильтры по `plants.user_id`, связи устройств и 404-hiding не менялись.
Собственный legacy JWT больше не принимается защищёнными маршрутами.

В текущем API нет отдельных profile/settings routes или пользовательского endpoint
для notification outbox, поэтому переключать там нечего. Device ingestion routes
по-прежнему используют отдельный `device-token` и схему `Authorization: Device`.

## Статус реализации: шаг 6

`POST /api/v1/auth/register`, `/login`, `/refresh` и `/logout` больше не выполняют
legacy-логику и всегда отвечают `410 Gone` с единым `ApiErrorResponse` и кодом
`LEGACY_AUTH_DISABLED`. Они не читают request body, не создают пользователей или
refresh sessions и не выдают собственные JWT.

Из активного приложения удалены HMAC JWT provider, генератор JWT, Argon2 password
flow, rotation/revoke refresh sessions, `JwtConfig`, JWT environment variables и
неиспользуемые зависимости и legacy auth DTO. `UserApplicationService` теперь
содержит только бизнес-операции с растениями и устройствами.

Колонка `password_hash`, таблица `refresh_tokens` и старые Flyway-миграции
намеренно сохранены в базе. Неиспользуемые runtime repository/model для refresh
tokens удалены; физическое изменение схемы допускается только отдельной последующей
миграцией. Firebase user provider и Device Token не изменены.

## Статус реализации: шаг 7

Добавлен защищённый `GET /api/v1/auth/me`. Endpoint использует provider
`firebase-user` и возвращает только internal UUID, email из проверенного Firebase
principal и `emailVerified`. Firebase UID, ID Token, внутренние claims, password
hash и server credentials в wire-контракт не входят.

Существующие `GET /health/live` и `GET /health/ready` сохранены без изменений:
они возвращают только состояние процесса или доступности базы данных и не
раскрывают Firebase project ID, credentials или настройки проверки токенов.

## Статус реализации: шаг 8

Firebase authentication проверяется через `FakeFirebaseTokenVerifier`; тесты не
обращаются к Firebase Admin или внешней сети. Покрыты отсутствующий, пустой,
некорректный и истёкший токены, формирование principal, сопоставление Firebase UID
с internal UUID, email claims, оба режима auto-provisioning и отсутствие дублей.

PostgreSQL Testcontainer проверяет конкурентный первый вход и применение Flyway на
чистой и уже мигрированной схеме. Регрессионные route-тесты подтверждают изоляцию
растений и устройств, чтение latest/history после Firebase-аутентификации,
неизменный Device Token ingestion и отказ неверному device token. Legacy auth
endpoints проверяются на `410 Gone` и отсутствие собственных access/refresh tokens.
