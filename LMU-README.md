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

**Gegen das echte System verifiziert** (per Python-Referenzimplementierung, analog zum
DIZ-Consent-Vorgehen): die zwei gPAS-SOAP-Operationen sind **nicht** dieselbe
(`getOrCreatePseudonymFor` für beide Schritte), sondern zwei verschiedene:
`getPseudonymFor` (nur Lookup, erzeugt nichts) für den `arbeitsnummer`-Schritt, danach
`createPseudonymFor` (erzeugt immer neu) für den `vorgangsnummer`-Schritt. D.h. jeder
Verarbeitungslauf bekommt eine frische Vorgangsnummer, während die Arbeitsnummer bereits
existieren muss (schlägt fehl, falls nicht - siehe Javadoc in
`KeycloakGpasPseudonymGenerator`, falls sich das als falsche Annahme herausstellt). Auch
hier: Keycloak-**Password-Grant**, nicht Client-Credentials - `GpasKeycloakConfigProperties`
braucht daher zusätzlich `username`/`password` eines Service-Users (Client-ID/-Secret allein
reichen nicht).

Aktivieren: `APP_PSEUDONYMIZE_GENERATOR=GPAS_KEYCLOAK` (statt `GPAS`/`BUILDIN`).

### 2. Consent: Broad Consent von DIZ statt gICS direkt

Neu: [`DizConsentConfigProperties`](src/main/kotlin/dev/dnpm/etl/processor/config/DizConsentConfigProperties.kt),
[`KeycloakDizConsentService`](src/main/java/dev/dnpm/etl/processor/consent/KeycloakDizConsentService.java)

**Request-Format gegen das echte System verifiziert (nicht mehr nur Annahme):** Anders als
ursprünglich angenommen ist das **kein** FHIR-Search wie Pauls `GicsGetBroadConsentService`,
sondern ein simples `GET [uri]<ID>` mit direkt angehängter ID (kein
`domain:identifier`/`category`/`patient.identifier`-Query). `APP_CONSENT_DIZ_URI` muss daher
bereits alles bis inkl. Query-Präfix enthalten, z.B. `.../fhir/Consent?patient=`. Auth per
Keycloak-Bearer, aber **Password-Grant** (Client-ID/-Secret **und** Username/Passwort eines
Service-Users), nicht Client-Credentials. Der MVConsent kommt weiterhin unverändert eingebettet
im Mtb-JSON von Onkostar - daran wurde nichts geändert.

**Wichtig, ebenfalls verifiziert:** DIZ' Broad Consent ist über die **Fall-ID** (lokale
Fallnummer aus Onkostar) verknüpft, nicht über `patient.id` im Mtb-JSON (das ist die
FallnummerMV, s.u. - eine andere ID). Die Fall-ID kommt separat über den `X-Case-Id`-Header
(siehe `MtbFileRestController`/`CaseId` in `types.kt`) und wird nur zur DIZ-Consent-Abfrage
verwendet, nirgendwo sonst im Payload. `ConsentProcessor.consentGatedCheckAndTryEmbedding`
nutzt sie, wenn der Header gesetzt ist, sonst fällt sie zurück auf `patient.id` (z.B. für
Kafka-Requests, die den Header nicht kennen, oder für Pauls gics/gics_get_bc, die weiterhin
patientenbezogen abfragen).

Offen: ob die Response selbst ein Bundle oder eine einzelne Consent-Resource ist, war beim
Schreiben dieses Abschnitts noch nicht verifiziert - `KeycloakDizConsentService` parst daher
beides. Siehe dessen Javadoc für Details.

Aktivieren: `APP_CONSENT_SERVICE=diz_keycloak` (statt `gics`/`gics_get_bc`/`none`).

### 3. Geteilte Komponente: Keycloak-Token-Provider

Neu: [`KeycloakTokenProvider`](src/main/kotlin/dev/dnpm/etl/processor/keycloak/KeycloakTokenProvider.kt)
(package `dev.dnpm.etl.processor.keycloak`)

Client-Credentials-Flow per Default, cached den Access-Token bis kurz vor Ablauf. Zwei
Instanzen (gPAS, DIZ) mit eigenen Credentials, da unterschiedliche Keycloak-Clients/Realms.
DIZ braucht zusätzlich den Password-Grant (siehe oben) - dafür `username`/`password` im
`KeycloakClientConfig` setzen, sonst greift automatisch Client-Credentials (z.B. für gPAS,
dort bisher unverändert/unverifiziert).

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
   `bindings/README.md`, Abschnitt "LMU-Setup"). Für gPAS/DIZ/Keycloak liegen bereits welche
   im Repo (`diz-klinikum-subca-g3.pem`, `geant-tls-rsa-1-intermediate.pem`,
   `gpas-srvdiz089p-selfsigned.pem`) - nur bei abweichenden Hosts/neu ausgestellten
   Zertifikaten neu diagnostizieren.
3. **CSS/JS-Bundles bauen** (leicht zu übersehen, steht nur im Haupt-`README.md`, Abschnitt
   "Entwicklungssetup" - ohne diesen Schritt bleibt `src/main/resources/static/` leer und die
   komplette Oberfläche lädt ohne Styling, `main.css` liefert 404):
   ```bash
   npm install
   npm run build
   ```
4. Image lokal bauen (nutzt automatisch `BP_EMBED_CERTS=true` und die Bindings):
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
5. Start mit (aus `deploy/` heraus, IMMER mit beiden `-f`-Flags und explizitem
   Projektnamen - Compose merkt sich das nicht zwischen Aufrufen, und auf einem geteilten
   Server können andere Compose-Projekte denselben Default-Projektnamen "deploy" ziehen):
   ```bash
   docker compose -p mv64e-etl-processor -f docker-compose.yaml -f docker-compose.lmu-override.yml up -d
   ```
   Kontrollieren, dass wirklich das eigene Image läuft, nicht Pauls Default-Image (passiert,
   wenn `-f docker-compose.lmu-override.yml` vergessen wird):
   ```bash
   docker inspect mv64e-etl-processor-dnpm-etl-processor-1 --format '{{.Config.Image}}'
   # muss "mv64e-etl-processor:lmu-local" zeigen
   ```
   **Port-Konflikte:** Falls Port 8080 (oder ein anderer in der `.env` verwendeter Port)
   lokal schon belegt ist, zeigt der Container scheinbar undurchsichtige Fehler (z.B. ein
   404 von einer völlig anderen, fremden App statt vom ETL) - einfach den Port in der
   `.env` ändern (z.B. `DNPM_MONITORING_HTTP_PORT=8091`) und `docker compose ... up -d`
   erneut ausführen.
6. Monitoring-Oberfläche prüfen (Port aus `.env`, Pfad `/configs`) - zeigt
   Verbindungsstatus zu gPAS/gICS/DNPM:DIP sowie (LMU-Fork) eine Kachel "Letzte DIZ
   Consent-Abfrage" mit Zeitpunkt, angefragter Patient-ID, Erreichbarkeit, Consent-Status
   und Rohantwort der letzten tatsächlichen Anfrage. Fehler stehen zusätzlich im Log.

## Bewusst nicht gebaut (Scope-Grenze)

- Keine eigene Monitoring-UI-Kachel für gPAS-Keycloak (nur DIZ-Consent hat inzwischen eine,
  s.o. - `ConnectionCheckResult`/`ConnectionCheckService` in
  `src/main/kotlin/dev/dnpm/etl/processor/monitoring/`, `DizConsentConnectionCheckService`
  als Beispiel für eine weitere Subklasse). Bei Bedarf nachrüstbar.
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
