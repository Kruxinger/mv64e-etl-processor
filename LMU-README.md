# LMU-Fork des mv64e-etl-processor

Dieser Branch (`lmu-custom`) ist ein Fork von
[pcvolkmer/mv64e-etl-processor](https://github.com/pcvolkmer/mv64e-etl-processor) (Paul,
wartet die Kernlogik weiter, insbesondere das MV64e-Datenmodell). Strategie: Pauls ETL nutzen,
für den LMU-Workflow umbauen, seine Änderungen regelmäßig reinmergen - siehe
[CLAUDE.md](CLAUDE.md) für den Gesamtkontext der 3-teiligen MV64e-Strecke.

## Was gegenüber Pauls Original geändert wurde

Alle Änderungen sind additiv (neue Dateien + minimale, additive Ergänzungen in
`AppConfiguration.kt`/`AppConfigProperties.kt` - keine bestehende Logik wurde verändert), damit
`git merge upstream/master` möglichst konfliktfrei bleibt.

### 1. Pseudonymisierung: zweistufig statt einstufig

Neu: [`GpasKeycloakConfigProperties`](src/main/kotlin/dev/dnpm/etl/processor/config/GpasKeycloakConfigProperties.kt),
[`KeycloakGpasPseudonymGenerator`](src/main/kotlin/dev/dnpm/etl/processor/pseudonym/KeycloakGpasPseudonymGenerator.kt),
[`BearerTokenOutInterceptor`](src/main/kotlin/dev/dnpm/etl/processor/pseudonym/BearerTokenOutInterceptor.kt)

FallnummerMV → gPAS-Domain `arbeitsnummer` → Arbeitsnummer → gPAS-Domain `vorgangsnummer` →
Vorgangsnummer (= finales PatID-Pseudonym). Pauls Original macht nur einen direkten Call pro
Pseudonym-Art. Auth per Keycloak-Bearer-Token (SOAP-Header), nicht Basic-Auth.

Aktivieren: `APP_PSEUDONYMIZE_GENERATOR=GPAS_KEYCLOAK` (statt `GPAS`/`BUILDIN`).

### 2. Consent: Broad Consent von DIZ statt gICS direkt

Neu: [`DizConsentConfigProperties`](src/main/kotlin/dev/dnpm/etl/processor/config/DizConsentConfigProperties.kt),
[`KeycloakDizConsentService`](src/main/java/dev/dnpm/etl/processor/consent/KeycloakDizConsentService.java)

Gleiches FHIR-Search-Schema wie Pauls `GicsGetBroadConsentService` (`GET [uri]/Consent?...`),
aber Keycloak-Bearer statt Basic-Auth. Der MVConsent kommt weiterhin unverändert eingebettet im
Mtb-JSON von Onkostar - daran wurde nichts geändert.

Aktivieren: `APP_CONSENT_SERVICE=diz_keycloak` (statt `gics`/`gics_get_bc`/`none`).

### 3. Geteilte Komponente: Keycloak-Token-Provider

Neu: [`KeycloakTokenProvider`](src/main/kotlin/dev/dnpm/etl/processor/keycloak/KeycloakTokenProvider.kt)
(package `dev.dnpm.etl.processor.keycloak`)

Client-Credentials-Flow, cached den Access-Token bis kurz vor Ablauf. Zwei Instanzen (gPAS,
DIZ) mit eigenen Client-Credentials, da vermutlich unterschiedliche Keycloak-Clients/Realms.

### 4. TLS/Zertifikate

**Kein neuer Mechanismus nötig** - das Repo hat über Cloud Native Buildpacks (`bootBuildImage`)
bereits eine CA-Cert-Einspeisung (`bindings/ca-certificates/`, siehe
[`bindings/README.md`](bindings/README.md)). `BP_EMBED_CERTS=true` ist jetzt aktiviert
(`build.gradle.kts`). Die drei LMU-spezifischen Zertifikate (Keycloak-Intermediate,
gPAS-Selfsigned, DIZ-interne-CA - identisch zu denen aus dem Python-Prototyp) müssen nur noch
als `.pem` in `bindings/ca-certificates/` abgelegt werden, sobald ihr auf einem Rechner mit
Netzzugriff seid. Deckt automatisch alle ausgehenden HTTPS-Verbindungen ab (gPAS, DIZ, Keycloak,
DNPM:DIP), da alle HTTP-Clients hier das JVM-Standard-Truststore nutzen.

## Lokal getestet (jetzt, gegen den Mock-Service statt echtem DNPM:DIP)

`deploy/env-sample.lmu.env` ist eine vollständige, eigenständige `.env`-Vorlage -
ersetzt `env-sample.env` komplett für diesen Fork, nicht zusätzlich dazu verwenden. Standardmäßig
aktiv: `BUILDIN`-Pseudonymisierung, kein externer Consent-Service, `APP_REST_URI` zeigt auf
`examples/dev/testservice` (muss separat laufen, siehe dessen README). Die
Keycloak/gPAS/DIZ-Blöcke stehen fertig vorbereitet, aber auskommentiert drin - fürs Zielsystem
einfach umschalten (Details in den Kommentaren der Datei selbst).

Ich habe diesen kompletten Weg tatsächlich einmal live durchgespielt (Image bauen, Compose-Stack
hochfahren, Button im Testservice-Frontend klicken) und dabei zwei Stolpersteine gefunden und
gelöst - beide sind unten dokumentiert, damit sie beim nächsten Mal nicht erneut Zeit kosten:

1. Verifiziert: `docker compose -f docker-compose.yaml -f docker-compose.lmu-override.yml
   config` zeigt, dass die Override-Werte (u.a. `APP_REST_URI`, `APP_PSEUDONYMIZE_PREFIX`)
   tatsächlich gewinnen und nicht von Pauls `DNPM_*`-Zuordnung überschrieben werden.
2. Verifiziert: kompletter Rundlauf über den echten, containerisierten ETL - Sendung an
   `/mtb` → 202 Accepted → ETL pseudonymisiert (Präfix aus `.env` sichtbar im Pseudonym) →
   Weiterleitung an den Mock-Service kommt an.

## Was als Nächstes auf dem Zielsystem zu tun ist

1. `deploy/env-sample.lmu.env` nach `deploy/.env` kopieren und ausfüllen
   (Keycloak Client-IDs/Secrets, gPAS SOAP-Endpoint, DIZ-URI, DB-Zugangsdaten, und
   `APP_REST_URI` von "Mock-Service" auf die echte DNPM:DIP-URL umstellen). **Nie die
   ausgefüllte `.env` committen** - ist bereits über `.gitignore`
   (`/deploy/.env`) ausgeschlossen.
2. Die drei `.pem`-Dateien in `bindings/ca-certificates/` ablegen (Diagnose-Befehle siehe
   `bindings/README.md`, Abschnitt "LMU-Setup").
3. Image lokal bauen (nutzt automatisch `BP_EMBED_CERTS=true` und die Bindings):
   ```bash
   ./gradlew bootBuildImage --imageName=mv64e-etl-processor:lmu-local
   ```
   **Bekannter Stolperstein auf Windows mit Docker Desktop:** Falls das mit
   `'username' must not be null` beim Pull des Builder-Images fehlschlägt, liegt es am
   `credsStore: "desktop"`-Eintrag in `~/.docker/config.json`, mit dem die
   Buildpacks-Pull-Logik nicht klarkommt. Workaround, ohne die echte Docker-Config
   anzufassen - Gradle-Daemon stoppen und mit einer leeren, isolierten Docker-Config neu
   bauen:
   ```bash
   ./gradlew --stop
   mkdir -p /tmp/docker-config-no-credstore && echo '{"auths":{}}' > /tmp/docker-config-no-credstore/config.json
   DOCKER_CONFIG=/tmp/docker-config-no-credstore ./gradlew --no-daemon bootBuildImage --imageName=mv64e-etl-processor:lmu-local
   ```
4. Start mit (aus `deploy/` heraus):
   ```bash
   docker compose -f docker-compose.yaml -f docker-compose.lmu-override.yml up -d
   ```
   **Port-Konflikte:** Falls Port 8080 (oder ein anderer in der `.env` verwendeter Port)
   lokal schon belegt ist, zeigt der Container scheinbar undurchsichtige Fehler (z.B. ein
   404 von einer völlig anderen, fremden App statt vom ETL) - einfach den Port in der
   `.env` ändern (z.B. `DNPM_MONITORING_HTTP_PORT=8091`) und `docker compose ... up -d`
   erneut ausführen.
5. Monitoring-Oberfläche prüfen (Port aus `.env`) - zeigt Verbindungsstatus zu
   gPAS/gICS/DNPM:DIP; die neuen Keycloak-Pfade haben aktuell noch keine eigene
   Status-Kachel dort (bewusst nicht gebaut, siehe unten), Fehler stehen aber im Log.

## Bewusst nicht gebaut (Scope-Grenze)

- Keine eigene Monitoring-UI-Kachel für die Keycloak-Verbindungen (ConnectionCheckService
  ist ein `sealed class` in Pauls `monitoring`-Package - neue Subklasse wäre möglich, aber
  Thymeleaf-Template-Änderungen dafür standen nicht im Fokus). Bei Bedarf nachrüstbar.
- Keine Kafka-Anbindung für LMU (bewusste Entscheidung, siehe Chat-Verlauf: Kafka + ETL im
  selben Compose-Stack ohne HA bringt kaum Vorteile gegenüber REST für Ein- und Ausgang).

## Bekannter, vorbestehender Test-Fehler (nicht LMU-bezogen)

`GicsConsentServiceTest > convertGicsResultToMiiBroadConsent` und
`miiBroadConsentShouldNotBeConvertedAgain` schlagen bereits auf Pauls unverändertem `master`
fehl (verifiziert). Vermutlich ein Bug in `GicsConsentService.hashBundleEntry` (nutzt
`Random.Default.toString()`, das Format ist nicht das, was der Test erwartet). Nicht
LMU-Code, nicht hier gefixt.

## Git-Setup

```
origin    -> Kruxinger/mv64e-etl-processor (dieser Fork)
upstream  -> pcvolkmer/mv64e-etl-processor (Pauls Original)
```

Pauls Änderungen reinholen: `git fetch upstream && git merge upstream/master` auf diesem Branch.
