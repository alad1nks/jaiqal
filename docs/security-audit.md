# Аудит безопасности серверной части Жайқал

Дата аудита: 2026-08-16  
Последнее обновление: 2026-08-20  
Область: `server`, `core/api-contract`, Flyway-миграции, Docker/Compose, Gradle runtime-зависимости и CI  
Метод: статический анализ кода и конфигурации, повторная трассировка authentication/authorization и credential flows, просмотр resolved runtime classpath, OSV-SCA с пересечением findings с фактическим server runtime distribution, запуск доступных тестов. Динамический pentest развёрнутого сервиса, DAST, скан контейнерного образа и SBOM-анализ не проводились локально.

## Резюме

Критических и новых высоких уязвимостей не обнаружено. Реализация всех шагов
P0–P3 присутствует в рабочем дереве, включая abuse/load tests, repository security
gates и автоматический quarantine аномальных устройств. Кодовый backlog: пуст.
Действия, требующие доступа к внешним системам и реальной инфраструктуре, вынесены
в отдельный [`security-operations-runbook.md`](security-operations-runbook.md).

Положительные свойства реализации:

- пользовательские ресурсы выбираются с `user_id`, а доступ к чужим объектам возвращает нейтральный `404`;
- ESP32 идентифицируется отдельной схемой `Authorization: Device <token>`;
- raw device-токены и claim-коды не хранятся в БД; используются `SecureRandom` и SHA-256;
- SQL-параметры поступают через prepared statements; подтверждённой SQL injection не найдено;
- ответы об ошибках централизованы и не раскрывают stack trace/токены;
- ingestion публикует событие после commit, а alert transition и outbox записываются в одной транзакции;
- контейнер приложения работает от непривилегированного пользователя;
- Gradle wrapper содержит SHA-256 дистрибутива.

## Модель приоритета

- **Высокий:** реалистичный путь к существенной потере доступности/конфиденциальности или известная High advisory в runtime.
- **Средний:** эксплуатация требует аутентификации, компрометации устройства или ошибки deployment, но последствия существенны.
- **Низкий:** hardening/наблюдаемость; самостоятельная эксплуатация маловероятна.

## Найденные риски

### SEC-01 — Уязвимый PostgreSQL JDBC driver (высокий)

**Исходное доказательство.** На момент аудита в `gradle/libs.versions.toml:22` был зафиксирован `org.postgresql:postgresql:42.7.10`; resolved `runtimeClasspath` подтверждал эту версию.

Версия попадает сразу под две опубликованные advisory:

- [GHSA-98qh-xjc8-98pq / CVE-2026-42198](https://github.com/advisories/GHSA-98qh-xjc8-98pq): CPU exhaustion при SCRAM-аутентификации к вредоносному/скомпрометированному PostgreSQL; исправлено в `42.7.11`;
- [GHSA-j92g-9f8w-j867](https://github.com/pgjdbc/pgjdbc/security/advisories/GHSA-j92g-9f8w-j867): downgrade channel binding для `42.7.4`–`42.7.11`; исправлено в `42.7.12`.

**Воздействие.** При контроле DB endpoint или позиции MitM возможны отказ в обслуживании клиента и ослабление ожидаемой защиты SCRAM channel binding. Риск особенно значим для удалённой БД.

**Исправление.** Обновить pgjdbc минимум до `42.7.12`, пересобрать образ, повторно разрешить dependency tree и добавить автоматический SCA/Dependabot. Для удалённой БД включить проверяемый TLS и `channelBinding=require` после обновления.

**Статус 2026-08-16: устранено.** pgjdbc обновлён до актуального стабильного `42.7.13`. `dependencyInsight` и собранный `server/build/install/server/lib/postgresql-42.7.13.jar` подтверждают итоговую версию. OSV-Scanner 2.5.0 не находит advisory для `org.postgresql:postgresql:42.7.13`.

### SEC-02 — Нет глобальных лимитов запросов и rate limiting (высокий)

**Исходное доказательство.** В `plugins/Http.kt` устанавливались только JSON, CORS и `StatusPages`; отсутствовали ограничение размера тела, request timeout, rate limiting и лимиты конкурентных запросов. Публичный `/health/ready` на каждый вызов занимает DB connection (`DatabaseInfrastructure.kt:40-48`). Каждый Bearer-запрос выполняет криптографическую проверку Firebase (`Authentication.kt:29-39`), а SSE держит соединение бессрочно.

**Сценарии.** Неаутентифицированный клиент может многократно вызывать readiness/auth endpoints; аутентифицированный пользователь может открывать неограниченное число SSE-соединений или отправлять большие JSON bodies. Это расходует Netty workers, память, Firebase/DB ресурсы и пул из 10 соединений.

**Исправление.** На ingress и в приложении задать:

- максимальный body size (например, 64 KiB для CRUD и рассчитанный отдельный предел для telemetry batch);
- rate limits по IP до auth, по `userId`/`deviceId` после auth, отдельные жёсткие лимиты для `/health/ready`, `/devices/claim`, token rotation и telemetry;
- request/read/write/idle timeouts и ограничение одновременных SSE на пользователя/IP;
- дешёвый кэш readiness на короткий срок вместо DB query на каждый внешний запрос;
- ответы `413`/`429` через существующий `ApiErrorResponse` и нагрузочные тесты.

**Статус 2026-08-16: устранено в коде.** Добавлены Ktor `RequestBodyLimit` (64 KiB общий, 128 KiB telemetry batch), pre-auth token buckets для readiness, всего user API и telemetry, а также per-user/per-peer cap для SSE. `413`/`429` возвращают `ApiErrorResponse`; focused tests подтверждают, что `429` срабатывает до повторной Firebase/device verification. Лимитеры намеренно являются per-process fallback bulkhead; cluster-wide edge enforcement относится к OPS-03 операторского runbook.

### SEC-03 — Телеметрия позволяет неограниченно заполнять БД (высокий)

**Доказательство.** Проверяется только размер одного batch `1..100` (`TelemetryIngestion.kt:34-38`). `nextUploadSeconds` является рекомендацией в ответе и не применяется на сервере. Нет квоты, минимального интервала, суточного лимита или retention/partition cleanup; каждая новая `sequence` создаёт строку в `measurements`.

**Сценарий.** Владелец украденного device-токена или неисправная прошивка может непрерывно посылать уникальные sequence и исчерпать диск/IO PostgreSQL. Idempotency защищает только от повторения той же sequence.

**Исправление.** Ввести per-device token bucket, серверный минимальный интервал/суточную квоту, метрики аномальной частоты, автоматическую блокировку/ротацию с ручным восстановлением, retention и партиционирование measurements. Ограничения должны учитывать легитимный offline batch и применяться атомарно/кластерно, например через PostgreSQL или общий rate-limit store.

**Статус 2026-08-16: устранено.** PostgreSQL-backed fixed-window quota атомарно ограничивает measurement items между репликами; single и batch ingestion получают `429 RATE_LIMITED` до persistence. V7 переводит `measurements` на 16 фиксированных hash partitions по `device_id`, сохраняя уникальность `(device_id, sequence)` и composite FK latest-state. Runtime retention удаляет истёкшие по `received_at` строки ограниченными `FOR UPDATE SKIP LOCKED` batch-транзакциями, но сохраняет последнюю запись каждого device. Retention window не может быть короче history API range. Capacity monitor теперь агрегирует все leaf partitions и продолжает публиковать безопасный `SECURITY_CAPACITY_ALERT`. V9 и P3.8 добавляют bounded anomaly quarantine: один burst учитывается один раз, решение требует минимум три разных исчерпанных quota window, блокировка автоматически истекает и может быть вручную снята владельцем с audit trail.

### SEC-04 — Device authentication линейно сканирует таблицу устройств (средний)

**Доказательство.** `ExposedDeviceTokenAuthenticator` делает `DevicesTable.selectAll()` и сравнивает hash в JVM до первого совпадения (`TelemetryPersistence.kt:19-26`). В V1 отсутствует unique index на `devices.token_hash`.

**Воздействие.** Каждый неверный device-токен читает и декодирует все строки `devices`; стоимость атаки растёт линейно с парком устройств и усиливает SEC-02. Одновременно это дольше удерживает одно из 10 соединений пула.

**Исправление.** Новой Flyway-миграцией привести hashes к фиксированному формату, добавить unique index на `token_hash`, вычислять SHA-256 кандидата один раз и выполнять параметризованный `WHERE token_hash = ?`. Сохранить нейтральный `401`, проверить disabled state той же выборкой и добавить тест на план/index lookup. Для случайных 256-битных токенов индексированный lookup по hash не снижает практическую стойкость.

**Статус 2026-08-16: устранено.** V6 fail-closed проверяет 64-символьный SHA-256 hex, нормализует регистр, сужает колонку, добавляет CHECK и unique index `devices_token_hash_unique_idx`. Аутентификатор один раз вычисляет canonical hash кандидата и выполняет параметризованный `WHERE token_hash = ?`; disabled state читается той же строкой, неизвестный токен по-прежнему приводит к нейтральному `401`. Integration test проверяет migration, index metadata, `EXPLAIN` index plan и оба состояния устройства.

### SEC-05 — Отзыв Firebase-токенов выключен по умолчанию (средний)

**Доказательство.** `FIREBASE_CHECK_REVOKED_TOKENS=false` задан в `AppConfig.kt`, `.env.example` и `compose.yaml:32`. Firebase verifier передаёт этот флаг в `verifyIdToken`.

**Воздействие.** Украденный, явно отозванный токен или токен отключённого пользователя остаётся пригодным до штатного истечения ID token. Это документированная политика, но небезопасный production default.

**Исправление.** Сделать production fail-closed: требовать явного `true` для production profile либо завершать startup. Если стоимость remote check неприемлема, документировать максимальное окно риска и применить короткоживущий cache результата revocation с безопасной политикой отказа. Добавить deployment test с revoked/disabled user.

**Статус 2026-08-16: устранено в коде.** `APP_ENVIRONMENT=production` теперь не проходит startup validation без `FIREBASE_CHECK_REVOKED_TOKENS=true`; Firebase Admin уже передаёт этот флаг в `verifyIdToken`. Route tests подтверждают единый нейтральный `401` для revoked/disabled verification failures. Реальный Firebase smoke test вынесен в OPS-04 операторского runbook.

### SEC-06 — SSE переживает срок действия и отзыв токена (средний)

**Доказательство.** Токен проверяется только при открытии `/stream`; затем цикл `while (true)` не имеет максимального времени и не перепроверяет principal/ownership (`UserRouting.kt:63-79`). `VerifiedFirebaseToken` и `UserPrincipal` не содержат `expiresAt`.

**Воздействие.** Уже открытое соединение продолжает получать telemetry events после expiry ID token. При `FIREBASE_CHECK_REVOKED_TOKENS=false` отключение пользователя также не закрывает stream. После архивирования/изменения владения ownership повторно не проверяется.

**Исправление.** Передать `expiresAt` из decoded token в principal и принудительно завершать SSE не позже expiry (желательно ещё с коротким максимальным lifetime), после чего клиент переподключается с новым токеном. Периодически подтверждать ownership/active state или закрывать subscriptions по доменному событию. Ограничить число stream connections согласно SEC-02.

**Статус 2026-08-16: устранено.** Firebase `exp` теперь обязательно переносится из проверенного token claims в `UserPrincipal`; уже истёкший principal получает нейтральный `401`. Stream завершается по более раннему из token expiry и настраиваемого max lifetime (по умолчанию 300 секунд), а ownership перепроверяется каждые 30 секунд перед дальнейшей выдачей данных. Потеря ownership закрывает соединение. Per-user/per-peer connection cap из P0.3 сохранён. Route tests проверяют token expiry, max lifetime и потерю ownership, unit tests — обязательный `exp`, expired-token rejection и границы конфигурации.

### SEC-07 — Неполная валидация строк превращает ошибки клиента в 500 (средний)

**Доказательство.** Для `species`, `imageUrl`, `claimCode` и `firmwareVersion` нет явных максимальных длин (`UserApplication.kt:33-48`, `TelemetryIngestion.kt:45-70`), хотя БД ограничивает их соответственно 255/2048/64-hash/100 символами. `PutAlertRulesRequest.rules` не имеет раннего лимита размера. Отсутствует общий body limit.

**Воздействие.** Большие значения расходуют память/CPU; значения длиннее DB columns вызывают SQL exception и `500`, создавая дешёвый поток ошибок и шум. Недоверенный `imageUrl` сохраняется без схемы; при небезопасном отображении клиентом возможны нежелательные URI/контент.

**Исправление.** Централизованно валидировать длину и формат до repository: `species <= 255`, `imageUrl <= 2048` и только разрешённые `https` (при необходимости отдельная политика для dev), `firmwareVersion <= 100`, claim code строго ожидаемого hex-формата/длины, не более четырёх уникальных alert types. Возвращать стабильный `400 ApiErrorResponse`; добавить boundary/property tests.

**Статус 2026-08-16: устранено.** До persistence введены фиксированные границы: `species <= 255`, credential-free HTTPS `imageUrl <= 2048`, claim code строго `[0-9a-f]{32}`, нормализованный `firmwareVersion <= 100`, не более четырёх уникальных публичных alert types. Текстовые DB-поля дополнительно отклоняют control characters. Ошибки проходят через существующие `UserApiException`/`TelemetryValidationException` и `StatusPages` как стабильные `400 ApiErrorResponse`; boundary tests проверяют точные границы, overflow, схемы URL, credentials, claim format, firmware и размер/уникальность rules до вызова persistence.

### SEC-08 — DB TLS и разделение привилегий не обеспечиваются приложением (средний)

**Доказательство.** Любой `DATABASE_URL` принимается без проверки (`AppConfig.kt:37-43`), Hikari не задаёт SSL mode (`DatabaseInfrastructure.kt:21-35`), а один datasource одновременно запускает Flyway и обслуживает runtime queries (`DatabaseInfrastructure.kt:14-16`).

**Воздействие.** Ошибка production-конфигурации удалённой БД может передавать credentials/data без проверяемого TLS. Runtime account вынужден иметь DDL-права для миграций, увеличивая blast radius при будущей SQL injection/RCE.

**Исправление.** Для production требовать `sslmode=verify-full` и доверенный CA (или эквивалент cloud connector). Разделить migration credentials и runtime account; runtime выдать только необходимые DML/sequence privileges. Миграции выполнять отдельным deployment job до запуска приложения.

**Статус 2026-08-16: устранено на уровне приложения.** Production profile не стартует, если runtime `DATABASE_URL` не содержит `sslmode=verify-full&channelBinding=require`. Production server больше не запускает Flyway и отклоняет присутствие `MIGRATION_DATABASE_*`, поэтому DDL credential не попадает в runtime process. Отдельный `migrateDatabase` entry point принимает только migration credentials, требует verified TLS и отклоняет совпадение migration/runtime username, если runtime username также передан job. Production startup проверяет каталоги PostgreSQL и fail-fast отклоняет runtime role с elevated attributes, schema `CREATE`, владением объектами или наследуемым членством в роли-владельце. Development сохраняет явный fallback на локальные runtime credentials. Deployment guide фиксирует ownership transfer и grants/default privileges; сами PostgreSQL roles создаются инфраструктурой окружения.

### SEC-09 — Опасные production-параметры допускаются без fail-fast (низкий)

**Доказательство.** `ALLOWED_ORIGINS=*` явно включает `anyHost()` (`Http.kt:37-40`). Compose публикует PostgreSQL на всех host interfaces и имеет известный fallback password (`compose.yaml:3-11`), хотя файлы помечены как local development.

**Воздействие.** Если локальный Compose или dev defaults ошибочно используются на общем/production host, БД и browser API surface становятся шире ожидаемого. CORS сам по себе не является auth control, но wildcard мешает policy hardening.

**Исправление.** В production profile запрещать wildcard и `http://` origins, требовать явные secrets без fallback, не публиковать порт БД. Разделить `compose.dev.yaml` и production manifests; проверять конфигурацию при startup. TLS/HSTS/security headers лучше завершать на явно документированном reverse proxy.

**Статус 2026-08-16: устранено в коде и reference manifests.** Production profile запрещает wildcard/HTTP CORS, требует HTTPS public origin и trusted TLS ingress, возвращает `426 HTTPS_REQUIRED` без trusted HTTPS marker и добавляет HSTS. Локальный Compose явно development-only; применение реального ingress/network policy вынесено в OPS-03/OPS-08 операторского runbook.

### SEC-10 — Недостаточная security-аудитируемость (низкий)

**Доказательство.** Логируется метод/path/status/requestId, но нет отдельных безопасных audit events для claim device, rotate token, изменения правил/калибровки и повторяющихся auth/rate-limit отказов. При этом `ProvisionDevice` печатает raw device token и claim code в stdout (`ProvisionDevice.kt:31-33`).

**Воздействие.** Сложнее расследовать захват устройства и злоупотребления. Запуск provisioning из CI или среды с централизованным сбором stdout сохранит одноразовые credentials в логах.

**Исправление.** Добавить структурированные события без raw токенов/UID/email: actor internal ID (при допустимости политики), action, resource ID, result, requestId. Provisioning разрешать только в интерактивной операторской среде, явно предупреждать о stdout и запрещать CI invocation; лучше передавать секрет через выделенный секретный канал/одноразовый файл с правами `0600`.

**Статус 2026-08-20: устранено в коде.** Версионированная JSON-схема `SECURITY_AUDIT` покрывает claim, rotation, calibration, alert-rule replacement/acknowledgement, `401` authentication, `429` rate-limit rejections, quarantine/restore и Firebase provisioning outcomes. События содержат только allowlisted поля, внутренние UUID и валидированный requestId; raw credentials, claim code, Firebase UID, email, body и произвольные клиентские строки исключены. Provisioning запрещён при `CI=true`, требует явного operator confirmation и абсолютного нового пути, отклоняет symlink parent/existing file, создаёт credential file с `0600` и не печатает secrets. Подключение внешнего sink/alerts вынесено в OPS-09.

### SEC-11 — Supply-chain hardening не автоматизирован (низкий)

**Доказательство.** В CI нет dependency/container scan и SBOM; GitHub Actions и Docker base images закреплены тегами, а не immutable commit/digest. Известимая SEC-01 поэтому не блокирует сборку.

**Исправление.** Добавить Dependabot/Renovate, dependency review/OSV или OWASP Dependency-Check с политикой по severity, Trivy/Grype для final image, генерацию CycloneDX SBOM. Закрепить Actions по commit SHA, production base image по digest и определить SLA обновлений.

**Статус 2026-08-20: устранено в repository CI.** Все внешние GitHub Actions закреплены по полным commit SHA с release-tag comments, runner закреплён на `ubuntu-24.04`, Temurin build/runtime и Compose PostgreSQL — по multi-arch OCI digest. Gradle strict dependency verification использует committed SHA-256 baseline для исполняемых артефактов и plugins. Проверка POM/Gradle Module Metadata отключена (`verify-metadata=false`), поскольку Android Studio KMP import динамически разрешает служебные parent POM и при полном metadata verification делает sync нестабильным; это осознанное снижение защиты от подмены dependency metadata. JAR/AAR/ZIP продолжают проверяться, кроме явно доверенных неисполняемых source/Javadoc artifacts и точечно ограниченного `gradle-9.6.1-src.zip`; бинарный Gradle wrapper отдельно закреплён через `distributionSha256Sum`. Least-privilege `supply-chain` job собирает фактический `installDist` и финальный image, выпускает CycloneDX SBOM и блокирует fixed/unfixed High/Critical findings Trivy; Dependabot обновляет Gradle, Actions, Dockerfile и Compose inputs. Hosted registry/admission rollout вынесен в OPS-07/OPS-08.

### SEC-12 — SCA выявил другие уязвимые runtime-транзитивы (высокий)

**Доказательство.** После обновления pgjdbc выполнен OSV-Scanner 2.5.0 по временному `gradle/verification-metadata.xml`. Общий manifest содержит также build/test и другие project artifacts, поэтому его агрегированный результат нельзя считать server runtime результатом. Пересечение findings с фактическими JAR из `server/build/install/server/lib` даёт 21 package-advisory pair: 10 высокой и 11 средней важности, критических нет.

Затронуты runtime-компоненты:

- Jackson Core/Databind `2.19.1`, приходящие через Flyway; среди исправлений OSV указывает `2.21.4`/`2.21.5`;
- Netty `4.2.15.Final`, приходящий через Ktor/Firebase Admin; затронуты `netty-codec-compression`, `netty-codec-http` и `netty-codec-http2`, исправленная линия начинается с `4.2.16.Final`;
- Apache HttpComponents `httpclient5:5.3.1`, `httpcore5:5.2.4`, `httpcore5-h2:5.2.4`, приходящие через Firebase Admin; OSV указывает исправления `5.6.3` и `5.4.3`;
- OpenTelemetry API `1.57.0`, приходящий через Firebase Admin; OSV указывает исправление `1.62.0`.

**Воздействие.** На момент обнаружения набор включал High advisories для HTTP parsing/HTTP2/compression и Jackson, поэтому общий SCA-критерий P0 «известные High advisories отсутствуют» не был выполнен.

**Исправление.** Обновлять в первую очередь владельцев dependency graph — Ktor BOM, Firebase Admin и Flyway — до совместимых версий, которые разрешают исправленные транзитивы. Не форсировать отдельные библиотеки без compatibility tests. После каждого изменения повторять `dependencyInsight`, собирать `installDist`, пересекать SCA с фактическими runtime JAR и прогонять server/persistence tests.

**Статус 2026-08-16: устранено.** Поскольку актуальные Ktor `3.5.1`, Firebase Admin `9.10.0` и Flyway `11.13.2` ещё требовали уязвимые транзитивы, в server dependency graph добавлены централизованные security floors: Jackson `2.21.5`, Netty `4.2.16.Final`, HttpClient `5.6.3`, HttpCore/H2 `5.4.3`, OpenTelemetry `1.62.0`. `installDist` подтверждает эти версии; повторное пересечение OSV findings с фактическим runtime distribution пусто.

### SEC-13 — Provisioning credentials можно сохранить внутри repository/build context (средний)

**Доказательство.** `requiredCredentialsPath` требует только абсолютный путь и
нормализует его (`ProvisionDevice.kt:84-90`), а `writeCredentialsFile` проверяет
только непосредственный parent, отсутствие существующего файла и права `0600`
(`ProvisionDevice.kt:93-115`). Путь внутри checkout, например
`<repo>/server/device.credentials`, остаётся допустимым. При этом `.gitignore` и
`.dockerignore` не исключают provisioning-файлы, а `server/Dockerfile` копирует
`server`, `core` и `app` в build stage.

**Воздействие.** Ошибка оператора может поместить raw device token и claim code в
Git index, резервную копию, Docker build context или удалённый cache/daemon. Права
`0600` защищают локальное чтение, но не предотвращают отправку файла инструментам
сборки и его случайный commit.

**Исправление.** До создания файла разрешать только canonical/real parent вне
корня repository и его symlink aliases; предпочтительно дополнительно ограничить
назначение allowlisted secret directory или secret store. Добавить защитные
patterns для provisioning credentials в `.gitignore` и `.dockerignore`, тесты на
путь внутри checkout и ancestor-symlink. При обнаружении уже созданного файла в
репозитории считать device token скомпрометированным и ротировать его.

**Статус 2026-08-16: устранено.** Gradle provisioning task передаёт корень
checkout через обязательное system property; destination обязан иметь суффикс
`.credentials`, а его существующий parent разрешается через `toRealPath()` до
проверки границы repository. Поэтому прямой путь и ancestor-symlink внутрь
checkout отклоняются до генерации секретов. `.gitignore` и `.dockerignore`
исключают provisioning-файлы, regression tests покрывают отсутствие repository
root, неверный suffix, прямой внутренний путь и symlink alias.

### SEC-14 — Notification worker сохраняет произвольный текст исключения (низкий)

**Доказательство.** При ошибке sender значение `Throwable.message` без
семантической фильтрации записывается в `notification_outbox.last_error` до 4000
символов (`NotificationWorker.kt:70-72`). Текущий development sender получает
безопасный payload из event ID/action/type, однако интерфейс рассчитан на будущие
внешние каналы.

**Воздействие.** Исключение будущего FCM/APNs/webhook adapter может включить
endpoint, bearer credential, часть payload или PII. Тогда секрет останется в БД,
резервных копиях и административных выгрузках, несмотря на общую политику не
хранить raw credentials.

**Исправление.** Хранить фиксированный allowlisted error code/category и при
необходимости безопасный correlation ID; не сохранять `Throwable.message`.
Добавить тест с secret-bearing exception и проверкой отсутствия секрета в БД и
логах. Для production sender запретить payload/credential values в исключениях.

**Статус 2026-08-16: устранено.** Worker сохраняет только значение закрытого
`NotificationFailureCode`; неизвестные исключения получают общий
`DELIVERY_FAILED`, а typed adapter exception принимает enum без произвольного
message. V8 заменяет исторические непустые `last_error` безопасным кодом и
добавляет DB CHECK allowlist. Unit tests проверяют unknown/typed exception с
секретами, PostgreSQL integration test — отсутствие секрета в сохранённом поле.

### SEC-15 — Для чувствительных API-ответов не задана явная cache policy (низкий)

**Доказательство.** `configureHttp` добавляет HSTS в production, но не задаёт
`Cache-Control: no-store` для `/api/v1/**` и `/api/device/**`
(`Http.kt:192-210`). Эти ответы содержат пользовательские данные, а token rotation
возвращает одноразовый raw device token.

**Воздействие.** Shared proxy, browser cache, отладочный middleware или ошибочная
edge-конфигурация могут сохранить чувствительный ответ дольше ожидаемого. Для
POST-ответов вероятность ниже, но явный запрет кэширования устраняет зависимость
от поведения каждого посредника.

**Исправление.** Централизованно добавлять `Cache-Control: no-store` к
аутентифицированным user/device responses, включая ошибки, claim и rotation;
добавить `X-Content-Type-Options: nosniff` и `Referrer-Policy: no-referrer` как
baseline API headers. Закрепить route tests, не создавая отдельного формата
ошибок.

**Статус 2026-08-16: устранено.** Общий HTTP pipeline добавляет `Cache-Control:
no-store`, legacy-compatible `Pragma: no-cache`, `X-Content-Type-Options: nosniff`
и `Referrer-Policy: no-referrer` ко всем `/api/v1/**` и `/api/device/**` ответам,
включая `StatusPages` errors, claim/rotation и telemetry. Health endpoints не
получают sensitive cache policy. Route tests покрывают успешную token rotation,
ownership-hiding `404`, device-auth `401` и отсутствие этих headers на liveness.

## Не подтверждено как уязвимость

- SQL injection: динамический SQL ограничен внутренними enum/константами (`history` bucket и calibration SET); пользовательские значения параметризованы.
- IDOR/BOLA: просмотренные plant/device/telemetry/alert paths проверяют владельца; тесты подтверждают `404` для другого пользователя.
- Утечка credentials через HTTP logs: request headers/body не логируются, request ID проходит allowlist.
- CSRF: сервер не использует cookie-сессию; авторизация передаётся в explicit Authorization header.
- SSRF: `imageUrl` сохраняется, но сервером не загружается. Необходима клиентская URI-policy, указанная в SEC-07.

## Финальная повторная проверка 2026-08-16

- Все 13 ранее запланированных шагов P0–P2 имеют соответствующие изменения кода,
  migrations/configuration/CI и focused tests; незавершённых пунктов в старом
  backlog не найдено.
- Повторный обход user/device authentication, ownership checks, SQL construction,
  transaction boundaries и error responses не выявил нового пути обхода auth,
  BOLA/IDOR, SQL injection или утечки stack trace.
- Resolved runtime остаётся на pgjdbc `42.7.13`, Jackson `2.21.5`, Netty
  `4.2.16.Final`, HttpClient `5.6.3`, HttpCore/H2 `5.4.3` и OpenTelemetry `1.62.0`.
  Актуальные Netty advisories с исправлением в `4.2.16.Final` не затрагивают
  resolved floor. Это точечная сверка, не замена полному CI SCA/image scan.
- Все выявленные изменения, которые можно выполнить и проверить в коде,
  migrations, manifests, workflows или repository guards, реализованы. Внешнее
  production-состояние не является кодовым backlog и контролируется отдельным
  операторским runbook.

## Операционные действия вне репозитория

Все действия, требующие GitHub Admin, Firebase, database, ingress, registry,
Kubernetes или observability access, собраны в
[`security-operations-runbook.md`](security-operations-runbook.md). В этом аудите
они больше не смешиваются со статусом реализации кода. Repository guard проверяет
полноту OPS-01–OPS-10 и запрещает снова смешивать code/operator readiness.

## План устранения

### P0 — немедленно, до production rollout

1. **Выполнено 2026-08-16:** обновить pgjdbc до `42.7.12+`; установлено `42.7.13`, resolved runtime classpath проверен, SCA выполнен.
2. **Выполнено 2026-08-16:** устранить runtime dependency findings SEC-12; повторный SCA фактического server runtime не выявил findings.
3. **Выполнено в приложении 2026-08-16:** body/rate limits и SSE caps для auth, readiness и telemetry.
4. **Выполнено в приложении 2026-08-16:** добавлены атомарная PostgreSQL per-device ingestion quota и сигнал `SECURITY_CAPACITY_ALERT` по росту `measurements`/диска.
5. **Выполнено в приложении 2026-08-16:** production fail-fast требует Firebase revoked-token checking, HTTPS public API/trusted ingress и `sslmode=verify-full&channelBinding=require`.

Критерий готовности: известные High advisories отсутствуют; oversized body получает `413`, превышение лимита — `429`; нагрузочный тест не исчерпывает connection pool; revoked user не получает новый доступ.

### P1 — ближайший спринт

1. **Выполнено 2026-08-16:** V6 migration с canonical SHA-256 constraint/unique index для `devices.token_hash`; full scan заменён на параметризованный lookup.
2. **Выполнено 2026-08-16:** SSE ограничен сроком Firebase token и max lifetime, ownership периодически перепроверяется, per-user/per-peer connection cap действует.
3. **Выполнено 2026-08-16:** закрыты длины/форматы DTO из SEC-07, добавлены стабильные `400 ApiErrorResponse` и boundary tests до persistence.
4. **Выполнено 2026-08-16:** production runtime и Flyway разделены по процессам/credentials; runtime не получает migration secrets и не запускает DDL, добавлены deployment grants и privilege checks.

Критерий готовности: device auth имеет O(log N)/index lookup; stream закрывается на expiry; invalid boundary values не дают `500`; runtime DB role не имеет DDL.

### P2 — 1–2 следующих спринта

1. **Выполнено 2026-08-16:** V7 hash-partitioning measurements, bounded receipt-time retention с защитой latest-state и partition-aware capacity alerts.
2. **Выполнено 2026-08-16:** добавить безопасный `SECURITY_AUDIT` trail и operator-only provisioning в новый `0600` файл без credentials в stdout.
3. **Выполнено 2026-08-16:** автоматизировать dependency/image scanning, CycloneDX SBOM, Gradle checksum verification и immutable pinning Actions/base images.
4. **Выполнено 2026-08-16:** добавить abuse/load tests для invalid token flood, readiness flood, oversized JSON, telemetry burst и SSE fan-out; PostgreSQL suite также проверяет возврат connections в bounded pool.

### P3 — hardening по итогам финального аудита

1. **Выполнено 2026-08-16:** SEC-13 закрыт — canonical path внутри repository/build context запрещён, ancestor symlinks учитываются, `.gitignore`/`.dockerignore` defense и regression tests добавлены.
2. **Выполнено 2026-08-16:** SEC-14 закрыт — `Throwable.message` заменён на allowlisted notification error codes, V8 очищает legacy values и закрепляет DB CHECK, добавлены negative secret-leak tests.
3. **Выполнено 2026-08-16:** SEC-15 закрыт — централизованно добавлены `Cache-Control: no-store`, `Pragma: no-cache` и baseline API security headers; success/error/health route tests закрепляют scope.
4. **Выполнено в репозитории 2026-08-20:** все доступные кодовые и policy-as-code gates SEC-02/03/05/08/09/10/11 реализованы. Фактическая настройка внешних систем выделена из кодового плана в OPS-01–OPS-10.
5. **Выполнено 2026-08-16:** после обновления Firebase Admin удалён неиспользуемый `google-cloud-storage` runtime graph. Firebase Auth compatibility test подтверждает сборку `FirebaseOptions`, инициализацию `FirebaseApp`/`FirebaseAuth` и отсутствие Storage classes. Обязательный для `FirebaseOptions` Jackson transport добавлен явно, а прежние совместимые версии Google OAuth/HTTP закреплены против скрытого downgrade. Firestore намеренно сохранён: `FirestoreOptions` входит непосредственно в бинарную поверхность `FirebaseOptions`, поэтому его исключение без замены всего Firebase Admin SDK небезопасно.
6. **Выполнено 2026-08-16:** supply-chain job до сборки сканирует чистый checkout Trivy `fs`: любой secret finding и High/Critical misconfiguration блокируют CI. Используется уже закреплённый Trivy Action/CLI; immutable-input guard не позволяет незаметно удалить оба gate. Политика запрещает маскировать найденный credential ignore-правилом без удаления, ротации и проверки истории.
7. **Выполнено 2026-08-16:** отдельный least-privilege CodeQL job анализирует Java/Kotlin через manual build `:server:classes` и расширенный `security-extended` query suite. `init`/`analyze` закреплены на полном commit SHA CodeQL Action `v4.37.3`; guard фиксирует language, build mode, query suite, build scope и оба action references. Результаты публикуются в GitHub code scanning через изолированное `security-events: write` permission.
8. **Выполнено 2026-08-16:** V9 и атомарный PostgreSQL detector учитывают не более одного нарушения на quota window и требуют минимум три разных исчерпанных окна внутри bounded observation horizon. Карантин временный (5 минут–7 дней), возвращает `403 DEVICE_QUARANTINED`/`Retry-After`, не меняет permanent `disabled_at` и создаёт один безопасный `QUARANTINE_DEVICE` audit event после commit. Владелец может идемпотентно очистить anomaly state через `/api/v1/devices/{deviceId}/restore`; чужой ресурс скрыт `404`, попытка и результат фиксируются как `RESTORE_DEVICE`.
9. **Выполнено 2026-08-16:** добавлен проверяемый Kubernetes production baseline: restricted Pod Security, non-root UID/GID, read-only root filesystem, `allowPrivilegeEscalation: false`, `privileged: false`, drop `ALL`, RuntimeDefault seccomp, выключенные host namespaces/service-account token, CPU/RAM/ephemeral-storage limits и bounded tmpfs. Runtime credentials проецируются read-only; `DATABASE_PASSWORD_FILE` и migration-аналог исключают пароль БД из env и fail closed при двух источниках. Default-deny NetworkPolicy разрешает только labelled ingress, DNS, PostgreSQL и controlled HTTPS egress proxy. Reference KubeletConfiguration фиксирует `podPidsLimit: 256`; CI structural guard и Trivy защищают baseline от ослабления. Применение в cluster относится к OPS-08.
10. **Выполнено 2026-08-16:** Trivy сканирует фактический `installDist` и сравнивает Medium findings с точным reviewed baseline `(vulnerability ID, package, installed version)`; новая уязвимость или новая версия пакета блокирует CI до отдельного review. Gradle `check` и supply-chain job независимо проверяют resolved runtime artifacts и содержимое JAR, запрещая возврат `google-cloud-storage*` и классов `com.google.cloud.storage`.
11. **Выполнено 2026-08-16:** доверие к TLS proxy header ограничено непосредственным peer allowlist. Production требует непустой `TRUSTED_PROXY_CIDRS`; parser принимает только canonical literal IPv4/IPv6 networks, не выполняет DNS resolution и запрещает `/0`. `X-Forwarded-Proto: https` учитывается только для socket peer внутри allowlist, а `X-Forwarded-For` не используется как источник доверия. Route/config tests проверяют trusted/untrusted peer, IPv4/IPv6 boundaries и fail-fast для пустых, hostname, malformed, non-canonical и all-addresses CIDR. Kubernetes baseline получает список из reviewed ConfigMap, а runtime-policy guard запрещает удалить эту привязку.
12. **Выполнено 2026-08-16:** `/health/ready` использует общий для server instance bounded cache результата и coroutine single-flight. Кешируется только boolean `ready`/`unavailable`, без exception details; `READINESS_CACHE_TTL_MILLISECONDS` по умолчанию равен 1000 мс и fail-fast ограничен диапазоном 1–5000 мс. После истечения TTL первый запрос обязательно выполняет свежую DB-проверку, поэтому stale success не выдаётся, а конкурентные разрешённые probes ожидают один check вместо одновременного захвата нескольких pool connections. Unit, config и HTTP abuse tests проверяют single-flight, кеширование обоих исходов, точную границу expiry и один фактический check на burst.
13. **Выполнено 2026-08-16:** добавлен изолированный executable self-test для security guards. Medium fixtures принимают empty/exact-reviewed reports и отклоняют новую tuple и wildcard baseline. Negative fixtures проверяют Pod Security, service-account/host namespaces, UID/GID/seccomp/capabilities, immutable image, resources, production/revocation/TLS env, projected secrets, bounded tmpfs/probes, NetworkPolicy/PID limit, immutable CI inputs, DAST, signing/admission, observability и разделение code/operator документации. Guards получили явный optional fixture root/baseline, сохранив прежние production вызовы. Self-test использует только временные каталоги, сравнивает `git status` до/после и запускается отдельным least-privilege CI job; `verification`, `sast` и `supply-chain` зависят от него, поэтому проверяемые artifacts не используются до успешного self-test.
14. **Выполнено в репозитории 2026-08-16:** checkout отключают `persist-credentials`, PR Dependency Review закреплён по SHA и блокирует Moderate+, `CODEOWNERS` защищает security-critical paths, а supply-chain negative fixtures не позволяют удалить controls. `configure-github-security.sh` fail closed применяет и проверяет required checks, reviews, admin enforcement, force-push/deletion protection, secret scanning и push protection. Полный Git-history scan не выявил verified credentials. Фактическая hosted настройка вынесена в OPS-02.
15. **Выполнено в репозитории 2026-08-16:** least-privilege `staging-dast.yml` имеет manual, weekly и `workflow_call` entry points, exact commit binding и fail-closed matrix TLS/proxy/CORS/auth/ownership/JSON/body/rate/SSE. Credential bootstrap использует protected refresh/device tokens без trace/artifacts и гарантирует cleanup; mock transport self-test проверяет positive path, wrong commit и отсутствие credential leakage. Настройка environment/fixtures и реальный scan вынесены в OPS-06.
16. **Выполнено в репозитории 2026-08-16:** `publish-production-image.yml` связан с успешным same-repository CI push, protected Environment и least-privilege OIDC/package permissions; он публикует immutable digest, сканирует его, подписывает и создаёт проверяемые SLSA/CycloneDX attestations. Kubernetes baseline содержит exact identity Sigstore enforcement и fail-closed digest-only admission; structural guard и negative fixtures защищают весь release/admission contract. Hosted GHCR и cluster rollout вынесены в OPS-07/OPS-08.
17. **Выполнено в коде и репозитории 2026-08-20:** `SECURITY_AUDIT` и `SECURITY_CAPACITY_ALERT` имеют JSON payload `schemaVersion=1`; `PROVISION_USER` покрывает безопасные SUCCESS/REJECTED/FAILURE. Provider-independent observability policy фиксирует write-only ingestion, append-only immutable retention, reader/export controls, безопасный `requestId`, дедуплицированные alerts и delivery failure. Fail-closed verifier и negative fixtures включены в CI/supply-chain. Подключение production backend и acceptance evidence вынесены в OPS-09.
18. **Выполнено 2026-08-20:** кодовый backlog и внешняя эксплуатационная настройка разделены. `security-operations-runbook.md` содержит OPS-01–OPS-10 с владельцами, ручными действиями, критериями готовности и безопасным evidence. Отдельный CI/supply-chain guard и четыре negative fixtures требуют полноту runbook, ссылку из аудита, закрытый кодовый backlog и отсутствие смешанного code/operator статуса; security-документы защищены `CODEOWNERS`.

## Проверка и ограничения окружения

- `./gradlew :server:dependencies --configuration runtimeClasspath --offline` — успешно; подтверждён runtime graph.
- `bash scripts/verify-supply-chain-inputs.sh` — успешно; immutable pins и checksum baseline прошли repository guard.
- Повторная проверка P0.1/P0.2: `dependencies` и `installDist` подтверждают security floors и pgjdbc `42.7.13`; 40 server unit/route tests без Docker-dependent persistence suite прошли, `./gradlew :server:build -x test` успешен.
- OSV-Scanner 2.5.0: после P0.2 пересечение findings с JAR из `server/build/install/server/lib` пусто; временные verification manifests, JSON-отчёты и scanner binary не добавлены в репозиторий.
- Focused P0.3 tests: `413 PAYLOAD_TOO_LARGE`, `429 RATE_LIMITED` для readiness/user API/telemetry и concurrent SSE caps прошли; Firebase/device verifier не вызывается повторно после исчерпания bucket.
- Focused P0.4 tests: per-device quota возвращает `429 RATE_LIMITED`/`Retry-After` до persistence, config boundaries и capacity alert thresholds прошли; PostgreSQL concurrency test добавлен в integration suite.
- Focused P0.5 tests: production fail-fast отклоняет выключенный revocation check, небезопасный JDBC TLS, HTTP public URL/CORS и отсутствие trusted ingress; plain HTTP получает `426 HTTPS_REQUIRED` до DB/auth, trusted HTTPS — HSTS; revoked/disabled verification failures получают нейтральный `401`.
- Focused P1.1: canonical SHA-256 unit test и компиляция migration/auth/index integration tests прошли; исполнение PostgreSQL migration/`EXPLAIN` test требует Docker-compatible runtime.
- Focused P1.2: token-expiry/max-lifetime/ownership SSE route tests, expired-token authentication, Firebase `exp` parsing, config boundaries и connection-cap tests прошли.
- Focused P1.3: plant/species/image URL/claim code/firmware/alert-rule boundary tests и route-level `ApiErrorResponse` checks прошли; invalid inputs не вызывают persistence.
- Focused P1.4: production config отклоняет migration secrets в runtime process, migration config проверяет полный набор credentials, distinct username, URL credentials и verified TLS; отдельный PostgreSQL integration test проверяет Flyway migration-role и runtime role без schema `CREATE`/object ownership, его исполнение требует Docker-compatible runtime.
- Focused P2.1: retention config/history relationship, batch stop/work-cap semantics и capacity threshold tests прошли; V7 migration, 16 partition metadata, partition-aware capacity query и сохранение latest-state при retention скомпилированы в PostgreSQL integration suite, исполнение требует Docker-compatible runtime.
- Focused P2.2: auth/rate-limit и sensitive-mutation audit events проверены на action/result/target/internal IDs/requestId; provisioning tests подтверждают CI guard, explicit confirmation, absolute new file и `0600` permissions без stdout credentials.
- Focused P2.3: CI/Dependabot YAML валиден, все `uses:` закреплены по полным commit SHA, OCI inputs — по digest; Gradle strict verification baseline сгенерирован, а offline `:server:installDist` подтверждает полноту checksum-набора для server runtime. Локальный запуск image scan невозможен без Docker; workflow выполняет его на `ubuntu-24.04` runner.
- Focused P2.4: 5 abuse/load tests прогоняют 64 invalid-token requests, 64 readiness requests, 48 oversized JSON requests, 80 telemetry requests и два SSE fan-out по 128 конкурентных попыток; разрешённая работа строго ограничена configured caps, `500` отсутствуют, SSE leases освобождаются. PostgreSQL integration test на 64 readiness checks добавлен для контроля возврата connections и ограничения Hikari pool; его исполнение требует Docker-compatible runtime.
- Focused P3.1: 6 provisioning tests прошли; проверены обязательный repository root, suffix `.credentials`, прямой путь внутри checkout, ancestor-symlink внутрь checkout, внешний absolute path, CI/confirmation guards и одноразовая запись с `0600`.
- Focused P3.2: 2 unit tests проверяют unknown/typed notification exceptions с credential-bearing messages и получают только allowlisted codes. PostgreSQL integration test проверяет сохранённый `DELIVERY_FAILED`, отсутствие исходного секрета и отклонение произвольного значения DB constraint; V8 migration и тест скомпилированы, исполнение требует Docker-compatible runtime.
- Focused P3.3: user/device route tests подтверждают `no-store`, `no-cache`, `nosniff` и `no-referrer` на успешной token rotation, ownership-hiding `404` и device-auth `401`; liveness test подтверждает, что sensitive cache policy не применяется к health endpoint.
- Focused P3.5: Firebase Auth runtime compatibility test прошёл после исключения Storage; `dependencyInsight` не находит `google-cloud-storage`, но подтверждает сохранённый `google-cloud-firestore`. После явного добавления необходимого Jackson transport и фиксации прежних Google OAuth/HTTP compatibility versions `installDist` уменьшился со 170 до 154 JAR и с 91 120 до 85 716 KiB (−16 JAR, −5 404 KiB / 5,9%).
- Focused P3.6: CI YAML содержит отдельные fail-closed Trivy filesystem gates для `secret` (все severity) и `misconfig` (High/Critical) до build; supply-chain input guard проверяет их наличие и immutable SHA action pin. Локальный Trivy `0.74.0` с актуальным checks bundle не обнаружил секретов или High/Critical Dockerfile findings; те же проверки повторяются на `ubuntu-24.04` runner.
- Focused P3.7: CI YAML содержит отдельный `sast` job с `contents: read`/`security-events: write`, CodeQL Action `v4.37.3` закреплён полным commit SHA, Kotlin-compatible manual build ограничен `:server:classes`, включён `security-extended`. Локально проверяются YAML, immutable-input guard и тот же Gradle build; фактический CodeQL extraction/query run и SARIF upload выполняются только на GitHub runner с code scanning.
- Focused P3.8: config tests фиксируют минимум три breached windows, достаточный observation horizon и bounded quarantine; unit/route tests проверяют отсутствие persistence, стабильный `403 DEVICE_QUARANTINED` и `Retry-After`; ownership/audit tests проверяют ручное восстановление и скрывающий `404`. PostgreSQL integration test проверяет distinct-window deduplication, единственный transition audit, временный auth state и owner-only reset; его исполнение требует Docker-compatible runtime.
- Focused P3.9: Ruby YAML structural guard подтверждает restricted namespace, non-root/read-only/no-escalation/drop-all/seccomp context, immutable image reference, resource and tmpfs limits, read-only projected secrets, disabled token/host namespaces, probes, default-deny/allowlisted NetworkPolicy и bounded `podPidsLimit`. CI и supply-chain guard требуют эту проверку; config tests проверяют file-backed database password, запрет двойного источника, relative path и файл больше 4096 bytes. Фактические admission, CNI и kubelet checks требуют staging cluster.
- Focused P3.10: cache-safe `:server:verifyFirebaseStorageRuntimeGraph` успешно проверяет resolved runtime artifacts и все 154 runtime JAR. Trivy `0.74.0` с обновлёнными vulnerability/Java DB не обнаружил Medium findings в фактическом `installDist`, поэтому reviewed baseline пуст; comparison script успешно принял реальный JSON-отчёт. Supply-chain guard фиксирует отдельный JSON scan только для Medium и fail-closed сравнение с точным baseline: новые tuple отклоняются, исчезнувшие findings выводятся как кандидаты на удаление.
- Focused P3.11: production config принимает canonical IPv4/IPv6 ingress networks и отклоняет отсутствие allowlist, hostname, host bits, invalid prefix и `/0`; matcher проверяет адреса по bytes без DNS. Route test подтверждает, что поддельный `X-Forwarded-Proto: https` от peer вне CIDR получает `426` до DB readiness, а тот же запрос от разрешённого peer проходит. Kubernetes structural guard требует `TRUSTED_PROXY_CIDRS` из `jaiqal-runtime-config`.
- Focused P3.12: 2 unit tests проверяют single-flight на 32 конкурентных caller, кеширование `ready` и `unavailable`, а также обязательную свежую проверку ровно на TTL boundary. Config tests фиксируют диапазон 1–5000 мс; HTTP abuse test подтверждает, что восемь разрешённых конкурентных readiness probes выполняют один DB check, остальные запросы отсекаются прежним rate limit, а liveness остаётся доступным.
- Focused P3.13–P3.15: shell syntax и CI/DAST workflow YAML успешно проверены; `scripts/test-security-guards.sh` прошёл positive Medium/runtime/supply-chain/DAST fixtures, включая wildcard baseline, checkout credentials, Dependency Review, CODEOWNERS, hosted settings, mandatory DAST entry points/environment/checks/TLS/commit binding/SSE bound, token cleanup и запрет artifacts. DAST mock-run прошёл полный positive path, отклонил wrong commit и не раскрыл test credentials. Self-test подтвердил неизменность dirty worktree.
- Focused P3.16: release workflow и оба Kubernetes YAML успешно разобраны; `verify-image-release-policy.sh`, `verify-runtime-policy.sh`, `verify-supply-chain-inputs.sh` и `git diff --check` прошли. Общий self-test отклонил ослабления CI-success/same-repository binding, protected Environment, OIDC/package permissions, tag non-overwrite, exact-digest scan, signature/SLSA/CycloneDX creation и verification, exact issuer/identity, predicate contents, CUE claims, Namespace opt-in и fail-closed digest-only admission.
- Focused P3.17: unit tests разбирают security payload как JSON, фиксируют schema/version и точный allowlist полей, отсутствие placeholders и Firebase/credential/request-body fields; provisioning test проверяет безопасный internal UUID/requestId signal. Policy verifier и восемь negative fixtures проверяют append-only/immutable storage, retention, reader isolation, forbidden fields, provisioning/delivery alerts, обязательный delivery-failure test и корреляцию только по `requestId`. Фактический collector/backend и on-call route в локальном окружении отсутствуют, поэтому production acceptance evidence ещё не получен.
- Focused P3.15 server: `AppConfigTest` и `ApplicationTest` успешно проверяют production-required full lowercase deployment SHA, отказ при missing/short/uppercase значении, отсутствие header в development и точный `X-Deployment-Commit` на trusted production liveness. Реальный external scan не запускался: staging URL, Firebase refresh tokens, device token и fixture UUID не предоставлены.
- Focused P3.14 history: официальный TruffleHog `3.96.0` для Darwin arm64 скачан во временный каталог, SHA-256 `87478306b95ca2420cfb844b7582383ac60b922e262350a0088e797f328d2e62` сверен с release checksums. Полный local Git source scan с `--no-update --no-verification --fail --fail-on-scan-errors` завершился без scan errors: 0 verified credentials; 7 unverified JDBC connection strings не содержат credential material. JSONL-отчёты с потенциально чувствительными raw fields создавались только в `/private/tmp`, не в repository, и удалены после безопасной классификации.
- Focused P3.18: documentation verifier, четыре negative fixtures, общий `test-security-guards.sh`, supply-chain guard, YAML parsing и `git diff --check` прошли; удаление OPS-блока/hosted control, открытие code backlog и возврат смешанного статуса отклоняются.
- `./gradlew :core:api-contract:allTests` — не завершён: `jsBrowserTest` и `wasmJsBrowserTest` не обнаружили тесты; JVM/Android/iOS части до browser tasks были up-to-date/успешны. Полный воспроизводимый прогон закреплён в OPS-01.
- `./gradlew :server:test` — 109 из 110 тестов прошли; setup PostgreSQL integration test class завершился ошибкой из-за отсутствия Docker socket (`Could not find a valid Docker environment`).
- `./gradlew :server:build` — compilation/distributions выполняются, итоговая задача падает на том же Testcontainers limitation.
- `./gradlew :server:build -x test` — успешно.
- `task.md` отсутствует в рабочем дереве; аудит выполнен по `README.md`, `AGENTS.md` и доступной документации из `docs/`.

Требования к полному прогону с Chrome/Android SDK/Docker находятся в OPS-01, а
реальный DAST — в OPS-06 операторского runbook.
