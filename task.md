# Жайқал — финальное задание на KMP-фронтенд

## Цель

Создать с нуля production-ready Kotlin Multiplatform клиент проекта **Жайқал** для Android и iOS.

Сейчас в репозитории существует только готовый Ktor-бэкенд с PostgreSQL. Пользовательская авторизация бэкенда переводится на Firebase Authentication. Клиентского кода пока нет.

Приложение должно позволять пользователю:

- зарегистрироваться и войти через Firebase Authentication;
- восстановить сессию после перезапуска;
- просматривать и редактировать профиль;
- создавать и редактировать растения;
- привязывать ESP32 по одноразовому claim-коду;
- просматривать состояние устройства;
- калибровать датчик влажности почвы;
- видеть последние измерения влажности почвы, температуры и влажности воздуха, освещённости и других показателей, которые реально возвращает API;
- просматривать историю измерений на графиках;
- получать обновления во время работы приложения;
- просматривать предупреждения и менять правила предупреждений;
- получать push-уведомления, если соответствующий backend endpoint уже существует;
- просматривать ранее загруженные данные без интернета.

Использовать **Compose Multiplatform с общей UI, presentation, domain и data логикой**.

---

## Важный контекст авторизации

Использовать следующую схему:

```text
Android/iOS
    -> Firebase Authentication
    -> Firebase ID Token
    -> Authorization: Bearer <Firebase ID Token>
    -> Ktor API
    -> внутренний users.id UUID
```

Клиент:

- не регистрирует пользователя через старый backend endpoint;
- не отправляет пароль на Ktor;
- не получает собственный JWT от бэкенда;
- не хранит собственный access token;
- не хранит refresh token бэкенда;
- не реализует самостоятельное обновление JWT;
- не использует Firebase UID как идентификатор бизнес-сущностей;
- не взаимодействует с Device Token ESP32.

Firebase SDK отвечает за пользовательскую сессию и обновление Firebase ID Token. Ktor получает только актуальный ID Token.

ESP32 продолжает авторизоваться на сервере через собственный Device Token. Этот токен никогда не должен попадать в мобильное приложение.

---

## Правила работы

1. До внесения изменений изучи:
   - `README.md`;
   - `AGENTS.md`;
   - `settings.gradle.kts`;
   - version catalog;
   - convention plugins;
   - существующие backend-модули;
   - `:core:api-contract`, если он существует;
   - актуальные маршруты, DTO и документацию API;
   - текущую ветку миграции бэкенда на Firebase Auth.
2. Не переписывай и не реорганизуй backend без необходимости.
3. Не восстанавливай старые `/register`, `/login`, `/refresh` и `/logout` на Ktor.
4. Клиент должен соответствовать фактическому API. Не придумывай endpoint, если в бэкенде уже есть другой эквивалент.
5. Если нужного endpoint нет:
   - зафиксируй это как backend dependency;
   - создай клиентский интерфейс;
   - используй fake только в preview и тестах;
   - не поставляй fake-данные в production.
6. Сначала составь короткий план и выполняй шаги по порядку.
7. После каждого шага собирай затронутые Android/iOS targets и запускай подходящие тесты.
8. Используй версии из version catalog. Новые версии должны быть совместимы с текущими Kotlin, Compose Multiplatform, Ktor и Gradle.
9. Не создавай универсальные `BaseViewModel`, `BaseRepository`, generic MVI-фреймворк или собственную навигацию.
10. Не создавай Gradle-модуль на каждую маленькую feature. На старте предпочитай feature packages.
11. Не добавляй секреты, service account JSON, Firebase ID Token, FCM token и реальные production URL в Git или логи.
12. Не выполняй блокирующий I/O на main thread.
13. Сохраняй поведение Android и iOS одинаковым, кроме действительно платформенных возможностей.

---

# Целевая архитектура

Используй прагматичную layered architecture:

```text
Compose Screen
    -> Common ViewModel
    -> Repository interface
    -> Repository implementation
        -> Remote data source -> Ktor Client -> Ktor backend
        -> Local data source  -> SQLDelight -> SQLite
```

Поток данных к UI:

```text
Network / SQLite
    -> Repository Flow
    -> ViewModel StateFlow
    -> Compose UI
```

Сервер является source of truth. SQLite используется как offline cache и не должен незаметно перезаписывать более свежие серверные данные.

Firebase Authentication является source of truth только для состояния пользовательской сессии. Firebase не хранит растения, устройства, телеметрию и предупреждения.

## Рекомендуемая структура

Используй существующие эквиваленты, если они уже есть. Иначе:

```text
:core:api-contract       # Общие сериализуемые API DTO
:app:composeApp          # Общий KMP-код и Compose UI
:app:androidApp          # Android entry point, если нужен отдельно
iosApp/                  # Xcode iOS application
```

Внутри `composeApp`:

```text
composeApp/src/commonMain/kotlin/<base-package>/
├── app/
│   ├── App.kt
│   ├── AppState.kt
│   └── navigation/
├── core/
│   ├── common/
│   ├── designsystem/
│   ├── network/
│   ├── database/
│   ├── auth/
│   ├── lifecycle/
│   └── connectivity/
├── feature/
│   ├── auth/
│   ├── plants/
│   ├── plantdetails/
│   ├── devices/
│   ├── calibration/
│   ├── alerts/
│   └── settings/
└── di/
```

Каждая feature при необходимости может содержать:

```text
data/
domain/
presentation/
```

Use case создавай только тогда, когда в нём есть бизнес-логика или координация нескольких repositories. Простую операцию repository можно вызывать из ViewModel напрямую.

## Технические решения

Используй существующие эквиваленты или:

- Compose Multiplatform;
- AndroidX Lifecycle ViewModel в `commonMain`;
- type-safe Navigation Compose for Multiplatform;
- Kotlin coroutines, `Flow` и `StateFlow`;
- Ktor Client;
- kotlinx.serialization;
- kotlinx.datetime;
- SQLDelight;
- Koin с Kotlin DSL;
- Compose Multiplatform Resources;
- официальный Firebase Android SDK;
- официальный Firebase Apple SDK;
- Coil 3 только если API действительно возвращает изображения.

Ktor engines:

- Android — OkHttp;
- iOS — Darwin.

Не передавай API DTO напрямую в composables:

```text
API DTO <-> domain model <-> local database model
```

---

# Шаг 1. Создать KMP-приложение и базовую инфраструктуру

## Задачи

1. Создай Android- и iOS-приложения с общим `composeApp`.
2. Настрой package name и bundle ID через конфигурацию проекта, не придумывая новые идентификаторы при наличии согласованных.
3. Подключи:
   - Compose Multiplatform;
   - Navigation;
   - Lifecycle ViewModel;
   - Ktor Client;
   - kotlinx.serialization;
   - SQLDelight;
   - DI;
   - Compose Resources.
4. Настрой build types:
   - debug;
   - release.
5. Настрой окружения:
   - local;
   - production;
   - при существующей потребности staging.
6. Base URL не должен быть захардкожен внутри repositories.
7. Учти адреса локального бэкенда:
   - Android emulator;
   - iOS Simulator;
   - физическое устройство.
8. Добавь безопасную debug-сетевую конфигурацию только для local development. Не ослабляй production transport security.

## App shell

Создай:

- splash/loading state;
- root navigation;
- auth graph;
- main graph;
- обработку deep links;
- единый snackbar host;
- базовые loading, empty, error и offline-состояния.

Рекомендуемая основная навигация:

- **Растения**;
- **Предупреждения**;
- **Настройки**.

Устройства открываются из растения или отдельного flow привязки, а не требуют обязательной нижней вкладки.

## Design system

Создай небольшой design system:

- цвета;
- типографика;
- spacing;
- shapes;
- кнопки;
- text fields;
- cards;
- metric cards;
- status badges;
- loading indicators;
- empty/error/offline states.

Поддержи:

- светлую тему;
- тёмную тему;
- системную тему;
- Dynamic Color на Android только как необязательное платформенное улучшение.

Не усложняй дизайн-систему токенами, которые пока нигде не используются.

## Критерии

- Android-приложение запускается;
- iOS-приложение запускается;
- используется общий Compose UI;
- навигация работает на обеих платформах;
- local и production URL разделены;
- backend-код не продублирован во frontend-модулях.

---

# Шаг 2. Подключить Firebase Authentication

## Платформенные SDK

Используй официальные SDK:

- Android — Firebase Authentication Android SDK;
- iOS — Firebase Authentication Apple SDK.

Не добавляй стороннюю KMP Firebase-обёртку без доказанной необходимости и явного объяснения.

Платформенную интеграцию скрой за common-интерфейсом:

```kotlin
interface AuthProvider {
    val authState: StateFlow<AuthState>

    suspend fun signUp(email: String, password: String)
    suspend fun signIn(email: String, password: String)
    suspend fun sendPasswordReset(email: String)
    suspend fun sendEmailVerification()
    suspend fun reloadUser()
    suspend fun getIdToken(forceRefresh: Boolean = false): String?
    suspend fun signOut()
}
```

Пример состояния:

```kotlin
sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(
        val email: String?,
        val emailVerified: Boolean,
    ) : AuthState
}
```

Сделай Android- и iOS-реализации через platform source sets или `expect/actual`.

## Экраны

Реализуй:

- `LoginScreen`;
- `RegisterScreen`;
- `ForgotPasswordScreen`;
- `VerifyEmailScreen`.

Поддержи:

- email/password;
- валидацию email;
- требования Firebase к паролю без выдуманных несовместимых ограничений;
- понятное отображение ошибок;
- loading и блокировку повторной отправки;
- повторную отправку verification email;
- повторную проверку статуса email;
- logout;
- восстановление Firebase-сессии после перезапуска.

Google Sign-In и Sign in with Apple не должны блокировать базовый релиз. Реализуй их только отдельным подшагом, если соответствующие providers уже включены и присутствуют необходимые платформенные настройки.

## После входа

После успешной Firebase-аутентификации вызови существующий backend endpoint вида:

```http
GET /api/v1/auth/me
Authorization: Bearer <Firebase ID Token>
```

Используй фактический путь из backend-кода.

Backend создаёт или находит внутреннего пользователя. Клиент не создаёт внутренний UUID самостоятельно.

## Критерии

- регистрация и вход работают через Firebase;
- пароль не отправляется на Ktor;
- Firebase session восстанавливается;
- неподтверждённый email обрабатывается согласно backend policy;
- logout очищает пользовательское состояние;
- common tests используют fake `AuthProvider`;
- Firebase ID Token не выводится в лог.

---

# Шаг 3. Реализовать сетевой слой и Firebase-сессию

## Ktor Client

Настрой:

- JSON content negotiation;
- таймауты;
- безопасное логирование без `Authorization`;
- единый разбор успешных ответов;
- единый маппинг backend error contract;
- connectivity errors;
- cancellation;
- debug logging только без секретов.

Создай абстракции уровня проекта, например:

```kotlin
interface ApiClient
interface AuthenticatedRequestExecutor
interface BackendConfig
```

Не создавай отдельный wrapper для каждого HTTP-метода без необходимости.

## Добавление Firebase ID Token

Перед защищённым запросом:

1. Получить ID Token через `AuthProvider`.
2. Добавить:

```http
Authorization: Bearer <Firebase ID Token>
```

3. При первом `401`:
   - принудительно обновить Firebase ID Token;
   - повторить запрос ровно один раз.
4. Если повтор снова вернул `401`:
   - не создавать retry loop;
   - перевести сессию в состояние повторного входа либо показать управляемую session error.

Одновременное принудительное обновление токена синхронизируй через `Mutex`, чтобы параллельные запросы не запускали множество refresh-операций.

Не сохраняй Firebase ID Token в SQLDelight, preferences или собственное secure storage. Firebase SDK управляет своей сессией самостоятельно.

## Shared API contract

Если `:core:api-contract` уже совместим с KMP:

- подключи его к клиенту;
- не дублируй DTO.

Если он содержит JVM-only зависимости:

- не копируй весь модуль;
- вынеси только реально общие сериализуемые DTO в KMP-compatible часть;
- не переноси database/server entities.

## Критерии

- защищённые запросы используют Firebase ID Token;
- `401` вызывает не более одного force refresh;
- конкурентный refresh сериализован;
- отменённые запросы не превращаются в generic error;
- backend errors отображаются безопасно и понятно;
- собственных access/refresh-токенов в клиенте нет.

---

# Шаг 4. Добавить локальную базу и offline cache

Используй SQLDelight для кеширования:

- пользователя;
- растений;
- устройств;
- последнего состояния каждого устройства;
- измерений, необходимых для выбранных диапазонов истории;
- предупреждений;
- правил предупреждений;
- времени последней синхронизации.

Требования:

- server data является source of truth;
- UI может сразу показать cache, а затем обновиться из сети;
- неуспешное обновление не удаляет валидный cache;
- cache хранит server identifiers;
- logout удаляет пользовательские данные текущего аккаунта;
- cache разных аккаунтов не смешивается;
- чувствительные токены в SQLDelight не хранятся;
- миграции SQLDelight тестируются.

Определи явную sync policy:

- список растений — cache-first с последующим refresh;
- детали растения — cache-first с refresh;
- последнее измерение — cache-first, затем network/SSE;
- история — network-first с fallback на cache;
- предупреждения — cache-first с refresh;
- mutation — server-first, затем обновление cache.

Не реализуй сложную двустороннюю offline-синхронизацию создания и редактирования сущностей в первой версии. При отсутствии сети mutation должна завершаться понятной ошибкой, не создавая ложное серверное состояние.

---

# Шаг 5. Реализовать растения

## Экраны

Создай:

- `PlantsScreen`;
- `CreatePlantScreen`;
- `EditPlantScreen`;
- `PlantDetailsScreen`.

## Список растений

Карточка растения должна показывать доступные данные:

- название;
- вид растения, если он поддерживается API;
- изображение или локальный placeholder;
- влажность почвы;
- состояние устройства;
- время последнего измерения;
- активное предупреждение.

Состояния:

- loading;
- empty;
- content;
- cached/offline;
- recoverable error;
- pull-to-refresh.

Empty state должен предлагать:

- добавить растение;
- привязать устройство.

## Создание и редактирование

Используй фактические поля API. Валидируй:

- обязательное название;
- допустимые длины;
- пороги, если они входят в форму;
- server validation errors.

Не реализуй распознавание растения по фотографии.

## Детали растения

Покажи:

- название и изображение;
- online/offline состояние устройства;
- время последнего измерения;
- влажность почвы;
- температуру воздуха;
- влажность воздуха;
- освещённость;
- дополнительные показатели только при наличии в backend contract;
- активные предупреждения;
- историю;
- действия редактирования, привязки и калибровки.

Каждая метрика должна корректно показывать:

- значение и единицу измерения;
- отсутствие данных;
- устаревшие данные;
- время измерения;
- нормальное/предупреждающее состояние, если сервер предоставляет пороги.

Не выдавай локально придуманное заключение о здоровье растения за серверный диагноз.

---

# Шаг 6. История измерений и realtime

## История

Добавь диапазоны, поддерживаемые API, например:

- 24 часа;
- 7 дней;
- 30 дней.

Покажи отдельные или переключаемые графики:

- влажность почвы;
- температура воздуха;
- влажность воздуха;
- освещённость.

Требования:

- понятные оси и единицы;
- локализованные даты;
- корректная обработка пропусков;
- отсутствие соединения точек через большие разрывы, если это вводит в заблуждение;
- loading/empty/error states;
- downsampled/aggregated данные с сервера для больших диапазонов;
- доступность без обязательного различения только по цвету.

Используй существующую лёгкую chart library, если она уже есть и поддерживает KMP. Иначе реализуй узкоспециализированный line chart без создания собственного универсального chart framework.

## Realtime

Используй фактический механизм бэкенда. Если реализован SSE:

- подключайся только после аутентификации;
- передавай Firebase ID Token;
- применяй новые измерения к repository/cache;
- обновляй UI через Flow;
- делай reconnect с exponential backoff и jitter;
- не переподключайся после logout;
- при возвращении в foreground выполняй refresh;
- в background останавливай соединение, если это требуется платформой;
- не создавай бесконечный tight reconnect loop.

Не опрашивай сервер каждые несколько секунд, если SSE уже доступен.

Мобильное приложение не отвечает за постоянный мониторинг в фоне: ESP32 отправляет данные непосредственно на backend.

---

# Шаг 7. Привязка устройства и калибровка

## Привязка

Создай:

- `ClaimDeviceScreen`;
- `DeviceDetailsScreen`.

Пользователь вводит или сканирует одноразовый claim-код, который затем отправляется в пользовательский backend endpoint.

Клиент не должен:

- получать Device Token;
- показывать Device Token;
- сохранять Device Token;
- отправлять телеметрию от имени ESP32.

Обработай:

- неверный код;
- использованный код;
- истёкший код;
- устройство уже привязано;
- отсутствие сети;
- успешную привязку;
- повторный запрос после неопределённого сетевого результата.

Если QR scanning отсутствует в зависимостях, ручной ввод должен быть полностью рабочим. Сканирование можно добавить как платформенное улучшение.

## Калибровка влажности почвы

Создай пошаговый wizard:

1. Объяснение процедуры.
2. Получение сухого значения.
3. Получение влажного значения.
4. Проверка результатов.
5. Подтверждение и отправка.

Используй фактический backend contract. Если API возвращает несколько samples:

- показывай прогресс;
- используй согласованную backend-логику;
- не заменяй серверную валидацию клиентской.

Обработай:

- одинаковые wet/dry значения;
- обратное направление ADC;
- нестабильные samples;
- timeout;
- отключившееся устройство;
- отмену без сохранения;
- повторную калибровку.

---

# Шаг 8. Предупреждения и правила

Создай:

- `AlertsScreen`;
- `AlertDetailsScreen`, только если это оправдано объёмом данных;
- `AlertRulesScreen`.

Поддержи типы, реально реализованные backend:

- низкая влажность почвы;
- высокая температура;
- низкая температура;
- устройство offline;
- другие типы только из API contract.

Список должен показывать:

- active/resolved;
- растение;
- тип;
- время;
- измеренное значение и порог, если доступны;
- acknowledge action, если поддерживается API;
- cached/offline state.

Редактирование правил должно:

- валидировать пороги;
- позволять задавать duration, если поле есть в API;
- объяснять, что duration защищает от случайного одиночного измерения;
- не менять UI на сохранённое состояние, если сервер отклонил запрос;
- поддерживать сброс к серверным значениям после ошибки.

---

# Шаг 9. Настройки, локализация и доступность

## Настройки

Реализуй:

- язык: системный, казахский, русский, английский;
- тема: системная, светлая, тёмная;
- данные аккаунта;
- статус подтверждения email;
- повторную отправку verification email;
- версию приложения;
- privacy policy placeholder/configurable URL;
- diagnostics только в debug;
- logout.

Несекретные preferences сохраняй локально.

## Локализация

Все пользовательские строки должны находиться в Compose Multiplatform Resources.

Поддержи:

- `kk`;
- `ru`;
- `en`.

Не оставляй пользовательские строки в Kotlin-коде. После смены языка видимые экраны должны обновляться без перезапуска, если это поддерживается выбранной реализацией.

Форматируй:

- даты;
- время;
- числа;
- проценты;
- температуру;

с учётом locale. Единицы измерения должны быть единообразными.

## Доступность

- semantic labels;
- `contentDescription` там, где изображение несёт смысл;
- touch targets подходящего размера;
- достаточный contrast;
- поддержка увеличенного шрифта;
- статус не обозначается только цветом;
- графики имеют текстовое представление ключевых значений.

---

# Шаг 10. FCM и Crashlytics

Выполняй этот шаг после основной авторизации и API.

## Firebase Cloud Messaging

Подключи FCM на Android и iOS только если backend уже предоставляет endpoint регистрации пользовательского push token.

Создай common-интерфейс:

```kotlin
interface PushTokenRegistrar {
    suspend fun requestPermission(): PushPermissionResult
    suspend fun currentToken(): String?
    suspend fun syncToken()
}
```

Платформенные реализации должны:

- корректно запросить разрешение;
- получить FCM token;
- отправить его в backend с Firebase ID Token;
- обработать ротацию token;
- деактивировать регистрацию при logout, если backend это поддерживает;
- не логировать token;
- открывать нужное растение или предупреждение при нажатии на notification.

На iOS настройка должна учитывать APNs.

Если backend endpoint отсутствует:

- не отправляй token в выдуманный endpoint;
- оставь интерфейс и документацию;
- зафиксируй backend dependency.

## Crashlytics

Подключи платформенные SDK:

- Android Crashlytics;
- iOS Crashlytics.

Требования:

- debug collection можно отключить;
- release mapping/dSYM должны загружаться;
- не записывать пароли, ID Token, FCM token, Device Token и персональные данные;
- при необходимости создать минимальный common `CrashReporter`;
- non-fatal ошибки не должны дублироваться бесконечно.

Не подключай Firestore и Realtime Database.

---

# Шаг 11. Тесты

## Common unit tests

Покрой:

- переходы `AuthState`;
- login/register validation;
- восстановление Firebase-сессии через fake provider;
- вызов `/auth/me` после входа;
- добавление ID Token;
- однократный force refresh после `401`;
- отсутствие бесконечного retry;
- конкурентный refresh через `Mutex`;
- logout cleanup;
- cache-first поведение;
- разделение cache разных пользователей;
- создание и редактирование растения;
- mapping последних измерений;
- отсутствие отдельных метрик;
- выбор периода истории;
- обработку SSE и reconnect policy;
- привязку устройства;
- шаги калибровки;
- валидацию alert rules.

Используй coroutine test tools проекта. Добавляй Turbine только при отсутствии эквивалента.

## Integration tests

Проверь:

- Ktor serialization на sanitized JSON fixtures;
- соответствие актуальному `:core:api-contract`;
- mapping backend errors;
- SQLDelight migrations;
- SQLDelight queries;
- repositories с fake HTTP engine;
- auth interceptor с fake `AuthProvider`.

Тесты не должны обращаться к реальному Firebase или production backend.

## UI tests

Добавь сфокусированные тесты:

- login;
- register;
- verify email;
- empty plants;
- populated plants;
- plant details;
- частично отсутствующие readings;
- offline cache;
- claim-code errors;
- calibration;
- alerts;
- смена темы и языка.

Используй semantics-based selectors, а не только текстовые селекторы, зависящие от локализации.

## Platform verification

Проверь:

- Firebase Auth Android implementation;
- Firebase Auth iOS implementation;
- получение ID Token;
- восстановление сессии;
- deep link;
- push permission и notification tap, если FCM реализован;
- release-конфигурацию Crashlytics.

---

# Шаг 12. Документация и финальная проверка

Создай frontend README или `docs/frontend.md`.

Опиши:

- архитектуру;
- модули и packages;
- dependency graph;
- Firebase Auth flow;
- получение и обновление ID Token;
- взаимодействие с `/auth/me`;
- cache policy;
- lifecycle SSE;
- backend Base URL;
- запуск Android;
- запуск iOS;
- локализацию;
- тесты;
- известные ограничения.

## Ручная настройка Firebase

Создай отдельный checklist того, что должен сделать владелец проекта:

1. Создать или выбрать Firebase project.
2. Добавить Android app с правильным package name.
3. Добавить iOS app с правильным bundle ID.
4. Получить платформенные Firebase configuration files.
5. Включить Email/Password в Firebase Authentication.
6. При необходимости включить Google и Apple providers.
7. Для Google Sign-In настроить SHA fingerprints и iOS URL scheme.
8. Для Sign in with Apple настроить Apple capability и Firebase provider.
9. Для FCM на iOS настроить APNs key.
10. Настроить CI для платформенных Firebase-файлов согласно политике репозитория.
11. Не добавлять server service account JSON во frontend.

Отдельно укажи:

- Firebase platform configuration files не являются backend service account credentials;
- service account JSON должен существовать только в серверном окружении;
- frontend никогда не должен содержать приватный ключ Firebase Admin.

## Architecture decisions

Добавь короткие ADR:

1. Firebase Auth вместо собственной клиентской JWT-сессии.
2. Ktor backend как source of truth.
3. SQLDelight только как offline cache.
4. SSE вместо частого polling.
5. Shared Compose UI и common ViewModels.
6. Feature packages вместо большого количества Gradle-модулей на старте.
7. Официальные платформенные Firebase SDK за common interface.

## Финальные команды

Запусти реальные доступные эквиваленты:

```bash
./gradlew :core:api-contract:allTests
./gradlew :app:composeApp:allTests
./gradlew :app:composeApp:assembleDebug
./gradlew :app:composeApp:compileKotlinIosSimulatorArm64
```

Также запусти форматирование, lint и static analysis, используемые репозиторием.

Если iOS build невозможно выполнить в текущем окружении:

- не заявляй, что он прошёл;
- выполни доступную Kotlin compilation;
- укажи точную команду для запуска на macOS;
- перечисли непроверенные platform integration points.

---

## Non-goals

Не реализовывать:

- backend;
- ESP32 firmware;
- Device Token внутри мобильного приложения;
- MQTT;
- Bluetooth/Wi-Fi provisioning ESP32, если для него нет отдельного утверждённого протокола;
- Firestore;
- Firebase Realtime Database;
- AI-диагностику растения;
- распознавание вида растения;
- анализ фотографии;
- социальные функции;
- payments/subscriptions;
- совместное владение растением несколькими аккаунтами;
- сложную offline mutation queue;
- фоновый polling каждые несколько секунд;
- собственную навигационную библиотеку;
- generic MVI framework;
- универсальную chart framework;
- production Firebase/APNs credentials.

---

## Definition of done

Задача завершена, когда:

- Android и iOS запускают общее Compose Multiplatform приложение;
- клиент создан с нуля без повреждения готового backend;
- пользователь регистрируется и входит через Firebase Auth;
- Firebase session восстанавливается;
- Ktor получает Firebase ID Token;
- клиент не использует старые JWT/refresh endpoints;
- `401` вызывает максимум один force refresh;
- пользователь может просматривать, создавать и редактировать растения;
- устройство можно привязать claim-кодом;
- Device Token ESP32 отсутствует в клиенте;
- калибровка реализована;
- последние измерения отображаются корректно;
- история отображается на графиках;
- realtime обновления работают в foreground;
- cache доступен offline;
- предупреждения и правила работают;
- `kk`, `ru`, `en` локализации завершены;
- светлая и тёмная темы работают;
- FCM и Crashlytics подключены либо документирован конкретный backend/config blocker;
- токены и секреты не логируются и не попадают в Git;
- common, Android и доступные iOS проверки проходят;
- критические flows покрыты тестами;
- README содержит ручные шаги настройки Firebase.

## Что предоставить после выполнения

1. Краткое резюме.
2. Список созданных модулей.
3. Список реализованных экранов.
4. Список использованных backend endpoints.
5. Список таблиц SQLDelight.
6. Результаты сборки, тестов, lint и static analysis.
7. Результат Android-сборки.
8. Результат iOS-компиляции или точные непроверенные пункты.
9. Ручные действия, оставшиеся в Firebase Console, Apple Developer и CI.
10. Backend dependencies и отсутствующие endpoints.
11. Отклонения от задания с объяснением причин.
