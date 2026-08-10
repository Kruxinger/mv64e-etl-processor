# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Spring Boot (Kotlin + Java, JDK 21) service that sits between Onkostar
(`mv64e-onkostar-plugin-export`) and DNPM:DIP in the DNPM-ETL pipeline for
Modellvorhaben Genomsequenzierung §64e. It accepts MTB files (REST or Kafka),
checks/embeds consent, pseudonymizes the patient ID, deduplicates, and
forwards to DNPM:DIP (REST or Kafka) — see `README.md` ("Einordnung innerhalb
einer DNPM-ETL-Strecke", `docs/etl.png`) for the full picture and all
user-facing configuration (`APP_*` env vars for pseudonymization, consent
services, security, transformations, Kafka topics, etc.) — that's the primary
reference, don't re-derive it from code when the README already documents it.

This is the **LMU fork** (`Kruxinger/mv64e-etl-processor`, upstream is
`pcvolkmer/mv64e-etl-processor`), developed directly on `master` (this fork
has no separate long-lived integration branch). LMU-specific additions
(two-step Keycloak-secured gPAS pseudonymization, Keycloak-secured DIZ
Broad Consent, CA-cert embedding, deploy tooling) are documented in
`LMU-README.md` — read that first if working on anything
Keycloak/gPAS/DIZ-related. See "LMU fork gotchas" at the end of this file
for non-obvious bugs already found and fixed while verifying the fork
against the real target systems, so they don't get re-investigated. For
local end-to-end testing without real gPAS/DIZ/DNPM:DIP, use
`examples/dev/testservice` (see its README) — its UI shows the full
sent/received MTB JSON, pretty-printed.

## Commands

```bash
./gradlew test                    # unit tests
./gradlew test --tests "dev.dnpm.etl.processor.services.ConsentProcessorTest"  # single test class
./gradlew test --tests "*.ConsentProcessorTest.shouldUseCaseIdInsteadOfPatientIdWhenPresent"  # single test method
./gradlew integrationTest         # integration tests (separate source set: src/integrationTest)
./gradlew allTests                # both
./gradlew spotlessCheck           # lint check (runs automatically before every Test task)
./gradlew spotlessApply           # auto-fix formatting
./gradlew jacocoTestReport        # coverage (depends on allTests)
./gradlew bootBuildImage          # build the runnable Docker image via Cloud Native Buildpacks
```

Formatting: Java via `googleJavaFormat` + import ordering, Kotlin via
`ktlint`, both wired into Spotless (`spotlessCheck` is a dependency of every
`Test` task, so a formatting violation fails `./gradlew test` too — run
`spotlessApply` first if in doubt). Java compilation runs NullAway in error
mode for the `dev.dnpm.etl` package tree.

Frontend assets (Thymeleaf pages' CSS/JS, source in `src/web/`, bundled via
rspack) are **not** produced by Gradle — run this once before
`bootBuildImage` or `bootRun` if `src/main/resources/static/` is missing/stale:
```bash
npm install
npm run build   # npm run dev for a watch build
```

Local dev server against `dev-compose.yml` infra (MariaDB/Postgres, Kafka):
```bash
SPRING_PROFILES_ACTIVE=dev ./gradlew bootRun
```

CI (`.github/workflows/ci.yml`) runs `test` and `integrationTest` as separate
jobs before building/pushing the image — mirror that when validating a
change (both matter; integration tests use Testcontainers + HtmlUnit and
aren't a subset of `test`).

## Architecture

Kotlin-first codebase with two Java pockets kept deliberately: consent
services (`src/main/java/.../consent/`) and gPAS pseudonymization
(`src/main/java/.../pseudonym/`) — match the existing language when editing
those, don't port them to Kotlin as a drive-by.

Package layout under `dev.dnpm.etl.processor` (mixed `src/main/kotlin` and
`src/main/java`, same package tree):

- **`input/`** — entry points: `MtbFileRestController` (`/mtb`, `/mtb/{id}`)
  and `KafkaInputListener`. Both funnel into `RequestProcessor`.
- **`services/`** — the pipeline itself. `RequestProcessor.processMtbFile()`
  is the orchestrator: consent check/embed → pseudonymize → transform →
  dedup (via `Fingerprint`) → send → persist `Request` + publish
  `ResponseEvent`. `ConsentProcessor` does the actual consent
  gate-and-embed logic (broad consent + genomDE consent, policy-code
  matching against the configured `IConsentService`). `TransformationService`
  applies configured `APP_TRANSFORMATION_*` JSONPath rewrites.
- **`consent/`** — `IConsentService` implementations, selected at
  `AppConfiguration` bean-wiring time by `app.consent.service`
  (`GicsConsentService`, `GicsGetBroadConsentService`, or `NONE` via
  `MtbFileConsentService`). Consent lookups happen *before* pseudonymization,
  against whatever identifier `ConsentProcessor` is given (default
  `mtbFile.patient.id`).
- **`pseudonym/`** — `Generator` implementations selected by
  `app.pseudonymize.generator` (`BUILDIN` = local SHA-256+prefix, `GPAS` =
  upstream single-call gPAS — REST or SOAP depending on whether
  `app.pseudonymize.gpas.uri` or `.soap-endpoint` is set, see
  `AppConfiguration`'s two separate `@ConditionalOnProperty` beans for it;
  `GPAS_KEYCLOAK` = the LMU fork's two-step, Keycloak-secured
  `KeycloakGpasPseudonymGenerator`, see `LMU-README.md` and "LMU fork
  gotchas" below). `PseudonymizeService` wraps whichever `Generator` is
  active and decides whether the configured
  `APP_PSEUDONYMIZE_PREFIX` gets applied — some generators already return
  gPAS's own final pseudonym and must not be re-prefixed, check the `when`
  branches there before assuming prefix behavior. The same `when`-branch
  pattern governs `PseudonymizeService.genomDeTan()`: `GPAS_KEYCLOAK`
  reuses its already-generated pseudonym as the genomDE transfer TAN
  instead of calling `Generator.generateGenomDeTan()` a second time.
- **`output/`** — `MtbFileSender` implementations (REST to DNPM:DIP, Kafka
  producer) selected the same conditional-bean way.
- **`monitoring/`** — the `Request` entity/repository (Spring Data JDBC,
  table `request`, migrations in `src/main/resources/db/migration/{mariadb,postgresql}/`
  — MariaDB and PostgreSQL migrations are kept in lockstep, add both when
  adding a column) plus `ConnectionCheckService` (sealed-class family of
  periodic reachability checks surfaced in the `/configs` monitoring UI via
  SSE).
- **`web/`** — Thymeleaf MVC controllers for the human UI (`/configs`,
  `/`, statistics) plus `StatisticsRestController` for the dashboard's
  chart data.
- **`security/`** — token-based auth for the `/mtb` endpoint and
  Spring-Security wiring (`AppSecurityConfiguration`), independent of the
  admin-user web login.
- **`config/`** — one `@ConfigurationProperties` class per external system
  (`GPasConfigProperties`, `GIcsConfigProperties`, `AppRestConfiguration`,
  `AppKafkaConfiguration`, `AppSecurityConfiguration`, ...) plus
  `AppConfiguration`, which is the single place all the "which
  implementation is active" `@ConditionalOnProperty`/`@Conditional` bean
  wiring lives. When adding a new pluggable implementation of `Generator`,
  `IConsentService`, `MtbFileSender`, or `ConnectionCheckService`, this is
  where it gets registered.

Config resolution: env vars are relaxed-bound to `@ConfigurationProperties`
(e.g. `APP_CONSENT_GICS_URI` → `app.consent.gics.uri`) — when a properties
class is bound under a nested prefix already, don't repeat that prefix in
the field name or the derived env var doubles up (e.g.
`GpasKeycloakConfigProperties` is bound at `app.pseudonymize.gpas.keycloak`,
so its fields are `username`/`password`, not `keycloakUsername`/
`keycloakPassword`).

Duplicate detection and "which submission type is this" (`INITIAL` /
`ADDITION` / `CORRECTION` / `FOLLOWUP` / `TEST`) both key off comparing the
new `Request`'s `Fingerprint` and `submission_type` against the patient's
prior `Request` rows — see `RequestProcessor.saveAndSend` before changing
anything in that area, the ordering of checks there is load-bearing.

## LMU fork gotchas

Non-obvious bugs found and fixed while verifying the fork against the real
target systems (Keycloak, gPAS, DIZ) — check here before re-investigating
something that looks LMU-specific:

- **Kotlin `@JvmInline value class` properties break in Thymeleaf/SpEL.**
  `CaseId`, `PatientId`, `PatientPseudonym`, `Tan`, `RequestId` etc. are all
  inline value classes. When a class property of that type (e.g.
  `Request.caseId`) is read via Thymeleaf's SpEL
  (`ReflectivePropertyAccessor`), it can't find the compiler-mangled
  getter and falls back to raw field access — the field itself is erased
  to the underlying type at the JVM level, so the template sees a plain
  `String`, not the wrapper. Templates must use `${request.caseId}`
  directly, **not** `${request.caseId.value}` (the latter throws
  `EL1008E: Property or field 'value' cannot be found on object of type
  'java.lang.String'` and crashes the whole page render — this is exactly
  what happened to `fragments.html`'s Fall-ID display, since fixed; see
  how `patientPseudonym`/`tan` are already used correctly there instead).
  This is unrelated to the `.value` calls on
  `RequestStatus`/`RequestType`/`SubmissionType`/`Severity` in the same
  templates — those are regular `enum class`es with a real `value`
  property and an unmangled getter, so they work fine as-is.
- **Arbeitsnummer is the PatID pseudonym, Vorgangsnummer is the genomDE
  transfer TAN — not the other way round.** `KeycloakGpasPseudonymGenerator`
  chains two gPAS domains: `arbeitsnummer` (looked up, stable per patient)
  then `vorgangsnummer` (created fresh every call, from the arbeitsnummer).
  An earlier version of this generator returned the Vorgangsnummer from
  `generate()` and used it as *both* the PatID pseudonym and the transfer
  TAN. That's wrong on two counts: it's not what LMU's gPAS domains
  represent (the Arbeitsnummer is the per-patient identifier; the
  Vorgangsnummer is a per-submission transaction number), and — more
  concretely — it silently broke `RequestProcessor`'s dedup/submission-type
  detection (`INITIAL`/`ADDITION`/`CORRECTION`/`FOLLOWUP`), which keys off
  `Request.patientPseudonym`: with a fresh Vorgangsnummer as the pseudonym on
  every call, no two submissions for the same patient ever pseudonymized to
  the same value, so the prior-request lookup never matched. Fixed: `generate()`
  now returns the Arbeitsnummer only; a separate `generateVorgangsnummer()`
  creates the Vorgangsnummer from an already-resolved Arbeitsnummer and is
  used only for the transfer TAN.
- **genomDE transfer TAN for `GPAS_KEYCLOAK`.** Upstream generates the
  genomDE transfer TAN (`metadata.transferTan`) from a *separate* gPAS
  multi-pseudonym domain (`APP_PSEUDONYMIZE_GPAS_GENOM_DE_TAN_DOMAIN`,
  default `"ccdn"`) via `Generator.generateGenomDeTan()`. LMU's gPAS has no
  such domain provisioned, and doesn't need one: the Vorgangsnummer, created
  fresh per submission from the Arbeitsnummer, is exactly what a transfer TAN
  needs (traceable back to the patient via the `arbeitsnummer` domain).
  `PseudonymizeService.genomDeTan()` special-cases this generator and calls
  `KeycloakGpasPseudonymGenerator.generateVorgangsnummer()` with the
  already-resolved Arbeitsnummer (the `patientPseudonym`) instead of
  resolving it a second time or requesting a pseudonym from the unconfigured
  `ccdn` domain; `Generator.generateGenomDeTan()` itself now throws
  `UnsupportedOperationException` if ever called directly on this generator
  (it isn't, via `PseudonymizeService`).
- **Shared `RestTemplate` bean and form-encoded requests.** The
  `RestTemplate` bean in `AppConfiguration.kt` is built via
  `RestTemplateBuilder.messageConverters(...)`, which *replaces* Spring
  Boot's default converters rather than appending to them.
  `FormHttpMessageConverter` must be explicitly included in that list, or
  Keycloak token requests (`application/x-www-form-urlencoded`, used by
  both gPAS's and DIZ's `KeycloakTokenProvider`) fail with "No
  HttpMessageConverter for ... LinkedMultiValueMap and content type
  application/x-www-form-urlencoded".
