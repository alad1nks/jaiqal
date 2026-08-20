# Ручная настройка production security

Этот документ содержит только действия во внешних системах, которые нельзя
завершить изменением исходного кода или CI-файлов репозитория. Кодовый backlog из
`security-audit.md` закрыт. Не отмечайте пункт выполненным по наличию manifest,
workflow или инструкции: требуется фактическое состояние целевой системы и
указанное evidence.

Рекомендуемый порядок: OPS-01 → OPS-02 → OPS-03/04/05 → OPS-06 → OPS-07/08 →
OPS-09 → OPS-10. Секреты, токены, полные HTTP-ответы и содержимое credential
files запрещено прикладывать к evidence.

## OPS-01 — Полный контрольный прогон

**Владелец:** разработчик или CI administrator.

- [ ] Установить Chrome и задать `CHROME_BIN`, если браузер не находится
  автоматически.
- [ ] Убедиться, что Android SDK доступен Gradle.
- [ ] Запустить Docker-compatible runtime и проверить доступность socket для
  Testcontainers.
- [ ] Выполнить из чистого checkout:

  ```bash
  ./gradlew :core:api-contract:allTests
  ./gradlew :server:test
  ./gradlew :server:build
  bash scripts/test-security-guards.sh
  ```

**Готово, если:** все команды завершились успешно без skip интеграционных тестов.
Сохранить ссылки на CI runs и commit SHA, но не загружать test responses или
временные credential files.

## OPS-02 — Защита GitHub repository

**Владелец:** GitHub repository administrator.

1. Сначала merge текущих workflow и `CODEOWNERS` в `main`, затем дождаться, чтобы
   каждый check хотя бы один раз появился в GitHub.
2. В **Settings → Rules → Rulesets** создать активный branch ruleset для `main`
   без bypass actors:
   - require pull request и минимум одно approval;
   - require Code Owner review, dismiss stale approvals и approval после
     последнего push;
   - require resolved conversations;
   - require strict/up-to-date checks `verification`, `sast`, `supply-chain` и
     `dependency-review` от GitHub Actions;
   - block force pushes и deletion; правила распространяются на administrators.
3. В **Settings → Code security** включить secret scanning и push protection.
4. Проверить состояние независимым чтением:

   ```bash
   gh auth login
   ./scripts/configure-github-security.sh --apply alad1nks/jaiqal main
   ./scripts/configure-github-security.sh alad1nks/jaiqal main
   ```

Скрипт — проверяемый эквивалент ручной настройки; токену нужен repository
Administration write, сам токен не печатается. Актуальное расположение UI и
смысл rules описаны в [GitHub rulesets](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/creating-rulesets-for-a-repository)
и [GitHub environments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).
Точное ожидаемое состояние также перечислено в
[`github-security-settings.md`](github-security-settings.md).

**Готово, если:** verifier завершается успешно, прямой/force push и PR без каждого
обязательного check/code-owner approval отвергаются. Evidence: URL ruleset,
скрин/экспорт настроек без секретов и ссылки на negative test PR.

## OPS-03 — Trusted edge, TLS и cluster-wide abuse controls

**Владелец:** ingress/network platform owner.

- [ ] Публиковать только HTTPS endpoint; Ktor port и PostgreSQL недоступны из
  клиентских сетей.
- [ ] Ingress удаляет клиентские `X-Forwarded-*`, сам выставляет
  `X-Forwarded-Proto: https`; `TRUSTED_PROXY_CIDRS` содержит только фактические
  direct-peer ingress CIDR после SNAT.
- [ ] TLS использует доверенную цепочку, современную конфигурацию и HSTS; HTTP
  либо перенаправляется до приложения, либо приложение отвечает `426`.
- [ ] На edge зеркально настроены body limits, per-client rate limits,
  connection/idle timeouts и SSE caps не слабее значений приложения. Заголовки
  `Retry-After` и `X-RateLimit-*` не удаляются.
- [ ] Kubernetes/VM firewall разрешает доступ к Ktor только trusted ingress.

**Готово, если:** внешний тест подтверждает HTTPS/HSTS, direct port закрыт,
поддельные forwarded headers не меняют trust decision, oversized request получает
`413`, burst — `429`, SSE закрывается сервером. Evidence: sanitized ingress policy,
firewall result и DAST run.
Связанные production variables и privilege checks описаны в
[`production-deployment.md`](production-deployment.md).

## OPS-04 — Firebase revocation smoke test

**Владелец:** Firebase administrator совместно с release owner.

1. Использовать отдельного staging user без production-доступа.
2. Получить ID token и подтвердить штатный `200` на защищённом endpoint.
3. Отозвать refresh tokens пользователя; отдельно повторить сценарий с disabled
   user. Не записывать ID/refresh token в shell history или artifacts.
4. Повторить запрос старым ID token и получить нейтральный `401` в обоих случаях.
5. Убедиться, что staging/production имеют
   `FIREBASE_CHECK_REVOKED_TOKENS=true`.

Официальное описание проверки отозванных токенов: [Firebase session
management](https://firebase.google.com/docs/auth/admin/manage-sessions).

**Готово, если:** revoked и disabled users получают `401`, а лог не содержит UID,
email или token. Evidence: timestamp, requestId, status codes и sanitized audit
search.

## OPS-05 — PostgreSQL TLS, migration role и runtime role

**Владелец:** database administrator.

- [ ] Создать отдельные роли `jaiqal_migrator` и `jaiqal_runtime`; не наследовать
  superuser/owner роли и не использовать один secret для обеих.
- [ ] Migrator получает только необходимые DDL/Flyway права и запускается
  one-shot job до rollout.
- [ ] После миграции ownership объектов передаётся контролируемой owner-role;
  runtime не владеет объектами, не имеет schema `CREATE`/DDL и получает только
  нужные DML/sequence privileges. Настроить default privileges для будущих
  миграций.
- [ ] Оба соединения используют `sslmode=verify-full`,
  `channelBinding=require` и доверенный CA. Secrets поступают из secret manager
  как read-only files; migration secrets отсутствуют в runtime Pod/process.
- [ ] Включить encrypted backups, restore drill, storage free-space alerts,
  autovacuum monitoring и retention, соответствующий требованиям данных.

**Готово, если:** migration job успешен; production startup принимает runtime
роль; попытки runtime выполнить `CREATE`, `ALTER` и прочитать системные/чужие
объекты отвергаются; сертификат с неверным hostname/CA не принимается. Evidence:
sanitized grants/role attributes, migration run, negative SQL checks и restore
drill. Общая модель PostgreSQL privileges описана в
[официальной документации](https://www.postgresql.org/docs/current/ddl-priv.html).

## OPS-06 — Реальный staging DAST gate

**Владелец:** staging и Firebase owners.

Выполнить полный setup из [`staging-dast.md`](staging-dast.md):

- [ ] создать две изолированные Firebase identities, две inert plants и отдельное
  устройство; не переиспользовать production fixtures;
- [ ] создать protected GitHub Environment `staging-security`, запретить
  self-review, разрешить deployment только из `main`, заполнить перечисленные в
  инструкции variables/secrets;
- [ ] развернуть exact commit за реальным TLS ingress с коротким staging SSE
  lifetime и корректным `X-Deployment-Commit`;
- [ ] запустить `.github/workflows/staging-dast.yml` и сделать job `dast`
  обязательным promotion gate;
- [ ] настроить weekly failure notification и процедуру ротации всех test
  credentials.

**Готово, если:** весь DAST matrix проходит против exact deployed SHA, wrong SHA
отвергается, promotion действительно ждёт `dast`, а workflow не создаёт artifacts
с ответами/секретами. Evidence: workflow/deployment URLs и fixture inventory без
credential values.

## OPS-07 — Production signing Environment и GHCR

**Владелец:** GitHub/GHCR release administrator.

- [ ] Создать protected Environment `production-signing`: required reviewer,
  prevent self-review, только branch `main`, admin bypass выключен.
- [ ] Не добавлять signing keys/PAT/cloud credentials: workflow использует GitHub
  OIDC и `GITHUB_TOKEN`.
- [ ] У GHCR package дать write только этому repository workflow; убрать user PAT
  и обычные CI publishers. Ограничить delete/package administration.
- [ ] Запустить release после успешного same-repository `CI` push в `main`.
- [ ] Зафиксировать выведенный digest и проверить для него image scan, keyless
  signature, SLSA provenance и CycloneDX attestation. Tag не использовать как
  deployment identity и не перезаписывать.

**Готово, если:** опубликован immutable `ghcr.io/...@sha256:...`, все три проверки
Cosign проходят с exact issuer/workflow identity, а иной actor не может публиковать
или двигать release version. Полная процедура — в
[`production-image-security.md`](production-image-security.md).

## OPS-08 — Kubernetes runtime и admission enforcement

**Владелец:** Kubernetes cluster administrator.

1. Использовать Kubernetes 1.30+; установить reviewed/pinned Sigstore Policy
   Controller и проверить у webhook `failurePolicy: Fail`.
2. Адаптировать selectors/ConfigMap/Secret placeholders в `deploy/kubernetes` к
   реальному cluster, не ослабляя manifests. Применить image verification policy,
   затем runtime policy.
3. Проверить Namespace `restricted` Pod Security, default-deny NetworkPolicy,
   ingress/DNS/PostgreSQL/controlled-egress allowlists, read-only projected
   secrets, non-root/read-only/drop-all/seccomp/resource/tmpfs controls.
4. Применить `podPidsLimit=256` или эквивалент на каждом worker node и проверить
   effective value.
5. Выполнить весь admission denial matrix из
   [`production-image-security.md`](production-image-security.md): tags, иной
   registry/issuer/workflow/branch, unsigned digest и отсутствующая attestation
   должны отклоняться; outage verifier должен fail closed.
6. Проверить нормальный rollback на ранее подписанный digest и двухоператорный,
   time-bounded break-glass без wildcard identity, `Warn` или tag deployment.

Kubernetes подтверждает стабильность `ValidatingAdmissionPolicy` с версии 1.30 и
требует binding с `validationActions: [Deny]`: [официальная
документация](https://kubernetes.io/docs/reference/access-authn-authz/validating-admission-policy/).

**Готово, если:** positive digest admitted, каждый negative case denied, policy
outage fail closed, runtime probes работают, Pod не может выйти за network/PID
границы. Evidence: cluster version, rendered manifest hashes, admission outputs и
rollback drill.

## OPS-09 — Централизованный security observability

**Владелец:** observability/SOC owner.

Выполнить [`security-observability.md`](security-observability.md) и перенести
точный contract из `deploy/observability/security-observability-policy.yaml` в
выбранный backend:

- [ ] collector parse-ит JSON message и write-only отправляет только
  `SECURITY_AUDIT`/`SECURITY_CAPACITY_ALERT` в отдельное append-only хранилище;
- [ ] immutable retention ≥365 дней, чтение только группе
  `security-incident-response`, exports требуют approval и сами аудируются;
- [ ] созданы дедуплицированные alerts для authentication/rate-limit bursts,
  quarantine, provisioning anomalies, capacity и сигнала
  `collector-export-failures`;
- [ ] первый delivery failure вызывает alert не позднее пяти минут, buffering и
  recovery проверены без потери/дублирования;
- [ ] генерация каждого сигнала, поиск по безопасному `requestId` и отсутствие
  forbidden fields подтверждены на staging.

**Готово, если:** acceptance matrix полностью пройден и notification дошёл до
реального on-call route. Evidence не должно содержать raw events с потенциальными
credentials; достаточно query/alert IDs, timestamps и sanitized field inventory.

## OPS-10 — Постоянные эксплуатационные процедуры

**Владелец:** security/release/operations owners.

- [ ] Назначить on-call и playbooks для credential leak, authentication burst,
  quarantine, capacity, failed DAST/CI/admission и audit delivery failure.
- [ ] При credential leak сначала revoke/rotate, затем чистить Git history;
  учитывать forks, clones, caches, workflow logs и artifacts.
- [ ] Для quarantined device исследовать firmware/token compromise до ручного
  `restore`; rotation и restore должны сохранять audit trail.
- [ ] Еженедельно разбирать Dependabot/CodeQL/Trivy/secret-scanning findings;
  исключение содержит exact finding, owner, reason, approval и expiry.
- [ ] Ежеквартально проверять readers/admins, environment reviewers, GHCR
  publishers, alert routes, retention lock, backups/restore, rollback digest и
  trust-root/identity rotation.
- [ ] После каждого существенного изменения ingress, Firebase, DB, registry,
  cluster или collector повторять соответствующий acceptance/negative matrix.

**Готово, если:** у каждого контроля есть владелец, периодичность, последний
успешный evidence и дата следующей проверки; просроченный контроль блокирует
production promotion.

## Итоговая фиксация

После выполнения OPS-01–OPS-10 сохранить в закрытой change/incident system:

- commit SHA и production image digest;
- ссылки на CI, DAST и release workflows;
- identifiers GitHub ruleset/Environments, cluster policies и observability
  rules (без secret values);
- даты и результаты negative tests, restore/rollback drills;
- владельца, reviewer и дату следующей проверки.

Только после этого внешний rollout можно считать завершённым. Сам факт наличия
файлов в `deploy/`, `.github/`, `scripts/` или `docs/` этого не доказывает.
