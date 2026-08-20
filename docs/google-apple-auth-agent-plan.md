# План для агента: код авторизации через Google и Apple ID

## Цель

Добавить в код Android- и iOS-приложений вход через Google и Apple ID, сохранив
существующие регистрацию, вход, подтверждение email и восстановление пароля через
Firebase Authentication.

Пользователей пока нет: проект начинает работу с пустой пользовательской базой.
Три способа авторизации — email/password, Google и Apple ID — должны остаться
постоянно доступными.

Внешние действия в Firebase Console, Google Cloud, Apple Developer, Xcode signing
и CI описаны отдельно в
[`google-apple-auth-human-setup.md`](google-apple-auth-human-setup.md).

## Исходная архитектура

- Email/password уже реализован через `AuthProvider` в `core/data`.
- Android использует `AndroidFirebaseAuthProvider`.
- iOS использует `IosFirebaseAuthProvider` и Swift-мост
  `app/iosApp/iosApp/FirebaseAuthBridge.swift`.
- UI и ViewModel находятся в `feature/auth`.
- `AppViewModel` в `app/shared` синхронизирует Firebase-сессию с backend через
  `GET /api/v1/auth/me`.
- Backend принимает только Firebase ID Token и автоматически создает внутреннего
  пользователя при первом валидном запросе.
- Серверные password endpoints остаются отключенными: email/password-вход
  выполняется клиентом непосредственно в Firebase.

## Ограничения

- Не удалять и не отключать email/password-функции.
- Не добавлять отдельные Google/Apple endpoints в backend.
- Не отправлять Google ID Token, Apple ID Token, OAuth access token, Apple
  authorization code, пароль или nonce в прикладной backend.
- Не сохранять эти значения в SQLDelight и не передавать их в логи или Crashlytics.
- Не менять device-аутентификацию `Authorization: Device <token>`.
- Не менять ownership-hiding `404`, `ApiErrorResponse` и ручную wiring-схему
  `Application.kt`.
- Не менять схему БД для обычного входа.

## 1. Добавить зависимости

В `gradle/libs.versions.toml` и соответствующих source sets:

- добавить Android Credential Manager;
- добавить Credential Manager adapter для Google Play Services;
- добавить библиотеку `googleid`;
- продолжить управлять Firebase Android зависимостями через существующий BoM;
- подключить GoogleSignIn для iOS после выполнения внешнего Xcode/SPM шага из
  human checklist.

Обновить `gradle/verification-metadata.xml` только штатным Gradle-механизмом,
проверив новые координаты и checksums. Не ослаблять strict dependency verification.

## 2. Расширить общий auth-контракт

В `core/data/src/commonMain/.../auth/AuthProvider.kt`:

- сохранить `signUp(email, password)` и `signIn(email, password)`;
- сохранить password reset, email verification, reload, ID Token и logout;
- добавить enum, например `FederatedAuthMethod { GOOGLE, APPLE }`;
- добавить метод федеративного входа, не возвращающий provider-токены в UI;
- при необходимости добавить platform-neutral механизм передачи presentation
  host без Android/iOS типов в common API;
- расширить `AuthErrorCode`:
  - `CANCELLED`;
  - `PROVIDER_UNAVAILABLE`;
  - `ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL`;
  - `CREDENTIAL_ALREADY_IN_USE`;
  - `INVALID_NONCE`.

Сохранить nullable email. Apple может использовать Private Relay, а повторный
ответ Apple может не содержать email.

Обновить:

- `UnavailableAuthProvider`;
- `FakeAuthProvider`;
- Android/iOS bridge interfaces;
- все реализации и тестовые doubles `AuthProvider`.

## 3. Реализовать Android Google Sign-In

В Android auth bridge:

- получать текущую `Activity` как presentation host, не удерживая ее сильной
  ссылкой в singleton-компоненте;
- создавать `GetGoogleIdOption` с Web OAuth client ID из Firebase resources;
- сначала использовать `filterByAuthorizedAccounts = true`;
- при отсутствии доступного credential повторять запрос с полным списком
  аккаунтов;
- принимать только `CustomCredential` типа Google ID Token credential;
- создавать Firebase credential через `GoogleAuthProvider.getCredential`;
- вызывать `FirebaseAuth.signInWithCredential`;
- преобразовывать отмену, отсутствие credential, неизвестный credential, сетевые
  ошибки и Firebase conflicts в стабильные `AuthErrorCode`.

Не логировать credential data и токены даже в debug-сборке.

## 4. Реализовать Android Apple Sign-In

В Android auth bridge:

- использовать `OAuthProvider.newBuilder("apple.com")`;
- запросить scopes `email` и `name`;
- запускать `startActivityForSignInWithProvider`;
- при создании или восстановлении Activity сначала проверять
  `FirebaseAuth.pendingAuthResult`;
- не запускать второй OAuth-flow, пока существует pending result;
- преобразовывать отмену, сетевые ошибки и Firebase conflicts в стабильные
  `AuthErrorCode`.

## 5. Обновить Android logout

- Сначала завершать Firebase-сессию.
- Вызывать `CredentialManager.clearCredentialState`.
- Сохранять существующую очистку account-scoped локального кеша.
- Ошибка очистки platform credential state не должна восстанавливать уже
  завершенную Firebase-сессию, но должна быть обработана без утечки данных.

## 6. Реализовать iOS Google Sign-In

Расширить `IosFirebaseAuthBridge`, `IosFirebaseAuthProvider` и
`FirebaseAuthBridge.swift`:

- получать текущий presenting `UIViewController`;
- настроить `GIDSignIn` с Firebase client ID;
- запускать `GIDSignIn.sharedInstance.signIn(withPresenting:)`;
- получать Google ID Token и access token только внутри Swift-моста;
- создавать Firebase credential через `GoogleAuthProvider`;
- вызывать Firebase `signIn(with:)`;
- преобразовывать cancel, network, unavailable и Firebase conflicts в стабильные
  строковые коды моста, затем в `AuthErrorCode`.

Provider-токены не должны пересекать Swift/Kotlin boundary.

## 7. Реализовать iOS Apple Sign-In

В Swift-мосте:

- для каждого запроса генерировать криптографически стойкий raw nonce;
- передавать в `ASAuthorizationAppleIDRequest` SHA-256 от nonce;
- хранить raw nonce только до завершения текущего запроса;
- запросить `.fullName` и `.email`;
- получить Apple identity token;
- создать credential через
  `OAuthProvider.appleCredential(withIDToken:rawNonce:fullName:)`;
- выполнить Firebase sign-in;
- очистить nonce после успеха, ошибки или отмены;
- преобразовать cancel, missing token и invalid nonce в стабильные ошибки.

Имя нужно передать Firebase при первом входе: Apple может не вернуть его повторно.

## 8. Обновить UI и ViewModel

В `feature/auth`:

- оставить поля email и пароля и кнопку «Войти»;
- оставить регистрацию, подтверждение email и восстановление пароля;
- добавить разделитель «или»;
- добавить кнопки «Продолжить с Google» и «Продолжить с Apple»;
- соблюдать официальные требования к внешнему виду provider buttons;
- добавить отдельный loading-state для текущего действия;
- блокировать повторные нажатия, пока действие выполняется;
- не показывать пользовательскую отмену системного окна как критическую ошибку;
- добавить RU/KK/EN строки и стабильные test tags;
- добавить понятные сообщения для новых `AuthErrorCode`.

Первый успешный Google/Apple sign-in одновременно создает Firebase user, поэтому
отдельный social registration screen не нужен.

В `AppViewModel`:

- сохранить email-verification gate для password accounts;
- не направлять social user на ручную верификацию, когда Firebase возвращает
  `emailVerified = true`;
- корректно обрабатывать social user с nullable или Private Relay email;
- сохранить backend session sync и refresh-on-401.

## 9. Обработать совпадение email

- Не объединять аккаунты автоматически только по email.
- При `ACCOUNT_EXISTS_WITH_DIFFERENT_CREDENTIAL` предложить войти ранее
  использованным способом.
- Не создавать backend merge endpoint.
- Явную привязку нескольких провайдеров к одному Firebase user не включать в
  обязательный объем этой задачи; при необходимости оформить ее отдельно и
  требовать повторную аутентификацию.

## 10. Проверить backend-регрессию

Функциональные изменения backend для обычного входа не нужны. Добавить или
уточнить тесты, подтверждающие:

- первый валидный Firebase UID атомарно создает внутреннего пользователя;
- способ получения Firebase-сессии не влияет на внутренний UUID;
- nullable email поддерживается;
- повторный запрос того же Firebase user возвращает того же внутреннего
  пользователя;
- Firebase UID, email и токены не попадают в security audit payload;
- protected routes продолжают применять ownership-hiding `404`.

## 11. Реализовать account deletion до iOS-релиза

В текущей реализации полный account-deletion flow не найден. Для соответствия
требованиям App Store потребуется отдельная кодовая задача:

- повторная аутентификация текущим способом входа;
- явное подтверждение;
- идемпотентное удаление серверных данных;
- удаление Firebase user;
- для Apple — получение свежего authorization code и отзыв токена;
- очистка локального account-scoped cache;
- безопасное восстановление после частичного сбоя между Firebase и PostgreSQL;
- защита от повторного auto-provision удаленного пользователя;
- новый номерной Flyway-файл, если выбранное решение меняет схему БД.

Все ошибки должны возвращаться в установленном формате `ApiErrorResponse` через
`StatusPages`. Не вводить route-specific error body.

## 12. Тестирование

### Unit и UI tests

- Существующие sign-in/sign-up/reset тесты продолжают проходить.
- ViewModel: Google, Apple, loading, cancel, duplicate tap и error mapping.
- Compose UI: email/password, Google, Apple, register и forgot-password actions.
- Android bridge: Google credential, unknown credential, no credential, Apple
  pending result и logout cleanup.
- iOS bridge: Google callback, Apple nonce, cancel, missing token и invalid nonce.
- `AppViewModel`: password user до/после email verification.
- `AppViewModel`: social user с verified, nullable и Private Relay email.
- Network: ID Token refresh и ровно один retry после `401`.

### Команды

Во время разработки запускать focused tests, затем:

```bash
./gradlew :feature:auth:allTests
./gradlew :core:data:allTests
./gradlew :app:shared:allTests
./gradlew :app:androidApp:assembleDebug
./gradlew :core:api-contract:allTests
./gradlew :server:test
./gradlew :server:build
```

Если `allTests` не запускается без Android SDK или persistence tests не запускаются
без Docker-compatible runtime, явно сообщить об ограничении окружения.

## 13. Документация

После изменения поведения обновить:

- `README.md` — все три способа входа и их общий Firebase ID Token flow;
- `docs/firebase-frontend-checklist.md` — убрать формулировки «если позже
  одобрено» для Google/Apple и синхронизировать реальные внешние шаги;
- `api.http` — только если появляется account-deletion endpoint;
- `.env.example` — только при новой backend-конфигурации.

Не коммитить `.env`, Firebase client config, Apple key или другие credentials.

## Критерии готовности к передаче человеку

- Код собирается с подготовленными внешними конфигурациями.
- Email/password регистрация, вход, verification и reset не сломаны.
- Google и Apple создают стандартную Firebase-сессию на Android и iOS.
- Backend получает только Firebase ID Token и возвращает стабильный внутренний UUID.
- Logout очищает Firebase-сессию, platform credential state и локальный кеш.
- Provider secrets и tokens не сохраняются и не логируются.
- Автоматические проверки пройдены либо ограничения окружения явно зафиксированы.
- Внешние шаги, необходимые для ручной приемки, отражены в human checklist.

## Ограничения исходных материалов

Предусмотренный инструкциями репозитория файл `task.md` отсутствует. План составлен
по `README.md`, текущей реализации и тестам авторизации.
