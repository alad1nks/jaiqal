# Настройка Google и Apple ID для человека

## Назначение

Этот checklist содержит только действия, требующие доступа к Firebase Console,
Google Cloud Console, Apple Developer, Xcode signing, CI secrets или реальным
устройствам. Изменения исходного кода описаны отдельно в
[`google-apple-auth-agent-plan.md`](google-apple-auth-agent-plan.md).

Итоговая конфигурация должна постоянно поддерживать три способа входа:

- email и пароль;
- Google;
- Apple ID.

Пользователей пока нет, поэтому очищать, переносить или вручную связывать аккаунты
не нужно.

## Перед началом

Подготовить доступы:

- роль администратора или достаточные права в Firebase project;
- доступ к связанному Google Cloud project;
- активное членство в Apple Developer Program;
- доступ к Certificates, Identifiers & Profiles;
- доступ к Xcode signing team;
- доступ к CI secret/file store;
- физические Android- и iOS-устройства для приемки.

Зафиксировать используемые идентификаторы:

- Firebase project ID;
- Android package name `com.alad1nks.jaiqal`;
- фактический iOS bundle ID, ожидаемо `com.alad1nks.jaiqal.Jaiqal`;
- Apple Team ID;
- Apple App ID;
- Apple Services ID;
- release signing certificates и Play App Signing certificate.

Не передавать private keys, пароли и service-account JSON через issue, чат,
репозиторий или build artifacts.

## 1. Проверить базовый Firebase project

В Firebase Console:

- [ ] Убедиться, что выбран project, соответствующий backend
      `FIREBASE_PROJECT_ID`.
- [ ] Проверить Android app с package name `com.alad1nks.jaiqal`.
- [ ] Проверить iOS app с фактическим bundle ID.
- [ ] Убедиться, что Email/Password включен и останется включенным.
- [ ] Проверить режим «один аккаунт на email».
- [ ] Проверить authorized domains.
- [ ] Не добавлять Firebase Admin service-account JSON во frontend project.

## 2. Включить Google Sign-In

В Firebase Console → Authentication → Sign-in method:

- [ ] Включить Google provider.
- [ ] Выбрать support email.
- [ ] Сохранить настройки.

В настройках Android app:

- [ ] Добавить SHA-1 debug certificate.
- [ ] Добавить SHA-256 debug certificate.
- [ ] Добавить SHA-1 release certificate.
- [ ] Добавить SHA-256 release certificate.
- [ ] Для Play-релиза добавить SHA-1 и SHA-256 из Play App Signing, а не только из
      upload key.
- [ ] Проверить, что создан Web OAuth client ID, необходимый Credential Manager.

В Google Cloud Console:

- [ ] Проверить OAuth consent screen, название приложения, support email и
      разрешенные scopes.
- [ ] Проверить Android OAuth clients для правильных package name и SHA.
- [ ] Проверить Web OAuth client, используемый Firebase Android config.
- [ ] Если OAuth consent screen находится в testing mode, добавить тестовые
      аккаунты или перевести конфигурацию в подходящий production status.

После сохранения настроек:

- [ ] Скачать новую версию `google-services.json`.
- [ ] Убедиться, что она содержит актуальный OAuth client configuration.
- [ ] Поместить файл как `app/androidApp/google-services.json` только в локальную
      или CI-среду; не коммитить его.

## 3. Настроить Google Sign-In для iOS

В Firebase Console:

- [ ] Скачать новую версию `GoogleService-Info.plist` после включения Google.
- [ ] Проверить `CLIENT_ID` и `REVERSED_CLIENT_ID`.

В Xcode:

- [ ] Добавить `GoogleService-Info.plist` в target приложения только через
      локальную/CI-конфигурацию проекта.
- [ ] Добавить URL Type со значением `REVERSED_CLIENT_ID`.
- [ ] Подключить официальный GoogleSignIn package через Swift Package Manager,
      если dependency еще не добавлена агентом в проектный файл.
- [ ] Проверить обработку входящего URL callback приложением.
- [ ] Проверить, что bundle ID совпадает с iOS app в Firebase.

## 4. Подготовить Sign in with Apple

В Apple Developer → Certificates, Identifiers & Profiles:

- [ ] Открыть App ID iOS-приложения.
- [ ] Включить capability Sign in with Apple.
- [ ] Сохранить App ID и обновить provisioning profiles при необходимости.
- [ ] Создать или проверить Services ID для browser flow.
- [ ] Связать Services ID с основным App ID.
- [ ] Настроить домен возврата Firebase.
- [ ] Добавить return URL:
      `https://<firebase-project-id>.firebaseapp.com/__/auth/handler`.
- [ ] Создать отдельный Sign in with Apple key или выбрать существующий
      управляемый key.
- [ ] Сохранить Key ID и один раз безопасно скачать `.p8` private key.
- [ ] Сохранить `.p8` в secret manager; не добавлять его в репозиторий, frontend
      resources или CI artifacts.

В Xcode:

- [ ] Выбрать правильную development team.
- [ ] Добавить Sign in with Apple capability в target.
- [ ] Убедиться, что entitlement присутствует в подписанной сборке.
- [ ] Обновить provisioning profiles после изменения capability.

## 5. Включить Apple provider в Firebase

В Firebase Console → Authentication → Sign-in method → Apple:

- [ ] Включить Apple provider.
- [ ] Указать Apple Services ID.
- [ ] Указать Apple Team ID.
- [ ] Указать Apple Key ID.
- [ ] Загрузить или безопасно передать соответствующий `.p8` key через интерфейс
      Firebase Console.
- [ ] Проверить Firebase OAuth redirect URI.
- [ ] Сохранить настройки.

После настройки не копировать Apple private key в Firebase client config и не
передавать его разработчикам, которым достаточно публичных client-настроек.

## 6. Настроить Apple Private Email Relay

Если Firebase отправляет verification, reset или другие account emails:

- [ ] Зарегистрировать домен/адрес отправителя Firebase в Apple Private Email
      Relay.
- [ ] Добавить стандартный Firebase sender вида
      `noreply@<firebase-project-id>.firebaseapp.com` либо фактический custom
      email domain.
- [ ] Проверить SPF/DKIM и custom email domain, если он используется.
- [ ] Отправить тестовое письмо на Apple Private Relay account.
- [ ] Убедиться, что письмо доставляется на реальный адрес пользователя.

## 7. Обновить CI secrets

- [ ] Сохранить `google-services.json` в CI file secret для Android jobs.
- [ ] Сохранить `GoogleService-Info.plist` в CI file secret для iOS jobs.
- [ ] Материализовать каждый файл только в соответствующем build job.
- [ ] Не публиковать эти файлы отдельными artifacts.
- [ ] Не помещать Apple `.p8`, Firebase Admin JSON или signing private keys во
      frontend artifacts.
- [ ] Проверить маскирование secret values в CI logs.
- [ ] Проверить удаление временных файлов после job.
- [ ] Сохранить production backend URL и signing settings в защищенной CI
      конфигурации, а не в Kotlin/Swift source.

`google-services.json` и `GoogleService-Info.plist` являются client config, а не
Firebase Admin credentials, но проект по существующей политике все равно хранит
их вне Git.

## 8. Проверить App Store требования

- [ ] Убедиться, что Google и Apple представлены на iOS как равноправные способы
      входа.
- [ ] Проверить соответствие Sign in with Apple button Human Interface Guidelines.
- [ ] Подготовить полностью рабочий demo account или инструкции для App Review.
- [ ] Убедиться, что reviewer сможет достучаться до production/staging backend.
- [ ] Реализовать и проверить инициируемое из приложения полное удаление аккаунта.
- [ ] При удалении Apple account проверять отзыв Apple token.
- [ ] Обновить privacy policy и App Privacy answers с учетом Google/Apple account
      data.
- [ ] Добавить нужные пояснения в App Review notes.

Account deletion — обязательное условие публикации iOS-приложения с созданием
аккаунта; одной деактивации или обращения в поддержку недостаточно.

## 9. Ручная приемка в test Firebase project

Сначала выполнить проверки в отдельном test project:

- [ ] Зарегистрироваться по email/password на Android.
- [ ] Подтвердить email, выйти и войти повторно.
- [ ] Проверить password reset.
- [ ] Впервые войти через Google на Android.
- [ ] Выйти и войти через другой Google account.
- [ ] Впервые войти через Apple на Android через browser flow.
- [ ] Прервать Google и Apple flow и проверить корректную отмену.
- [ ] Повторить Google flow на физическом iOS-устройстве.
- [ ] Повторить Apple flow на физическом iOS-устройстве.
- [ ] Проверить Apple Hide My Email.
- [ ] Перезапустить приложение и проверить восстановление Firebase-сессии.
- [ ] Проверить смену аккаунта после logout.
- [ ] Убедиться, что `GET /api/v1/auth/me` возвращает внутренний UUID для каждого
      нового Firebase user.
- [ ] Повторный вход того же Firebase user должен возвращать тот же внутренний
      UUID.
- [ ] Проверить понятное сообщение при совпадении email у разных способов входа.
- [ ] Удалить тестовый аккаунт и убедиться, что старый token больше не дает доступ.

Firebase Emulator не воспроизводит полноценные внешние Google/Apple OAuth flows,
поэтому одной emulator-проверки недостаточно.

## 10. Включить production providers

После успешной приемки кода и test project:

- [ ] Проверить production SHA fingerprints, bundle IDs, redirect URI и domains.
- [ ] Проверить production client config files в CI.
- [ ] Включить Google provider в production Firebase.
- [ ] Включить Apple provider в production Firebase.
- [ ] Оставить Email/Password включенным без срока отключения.
- [ ] Выпустить Android/iOS clients, поддерживающие включенные providers.
- [ ] Выполнить smoke test каждого способа входа после релиза.
- [ ] Проверить production logs на отсутствие токенов, email и credentials.

## Критерии завершения человеческой настройки

- Email/Password, Google и Apple включены в одном Firebase project.
- Android SHA fingerprints соответствуют фактическим signing certificates.
- iOS URL scheme, bundle ID, capability и provisioning profile корректны.
- Apple Services ID, key, domain и Firebase return URL согласованы.
- Private Email Relay доставляет письма.
- Client config files безопасно доступны локальным и CI builds, но отсутствуют в
  Git и artifacts.
- Все три способа входа проверены на физических Android/iOS-устройствах.
- Account deletion и Apple token revocation проверены до App Store submission.

## Официальные ссылки

- [Firebase: Google authentication on Android](https://firebase.google.com/docs/auth/android/google-signin)
- [Firebase: Apple authentication on Android](https://firebase.google.com/docs/auth/android/apple)
- [Firebase: Google authentication on Apple platforms](https://firebase.google.com/docs/auth/ios/google-signin)
- [Firebase: Apple authentication on iOS](https://firebase.google.com/docs/auth/ios/apple)
- [Apple App Store Review Guidelines](https://developer.apple.com/app-store/review/guidelines/)
- [Apple account deletion guidance](https://developer.apple.com/support/offering-account-deletion-in-your-app)
