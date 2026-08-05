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
`pcvolkmer/mv64e-etl-processor`). LMU-specific additions (two-step
Keycloak-secured gPAS pseudonymization, Keycloak-secured DIZ Broad Consent,
CA-cert embedding, deploy tooling) are documented in `LMU-README.md` — read
that first if working on anything Keycloak/gPAS/DIZ-related. `temp.txt` is a
running handoff log of fixes/gotchas found while verifying the fork against
the real target systems; check it before re-investigating something that
looks LMU-specific.

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
  `AppConfiguration`'s two separate `@ConditionalOnProperty` beans for it).
  `PseudonymizeService` wraps whichever `Generator` is active and decides
  whether the configured
  `APP_PSEUDONYMIZE_PREFIX` gets applied — some generators already return
  gPAS's own final pseudonym and must not be re-prefixed, check the `when`
  branches there before assuming prefix behavior.
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
