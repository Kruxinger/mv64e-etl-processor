# LMU-Fork des mv64e-etl-processor

Dieser Fork ([`Kruxinger/mv64e-etl-processor`](https://github.com/Kruxinger/mv64e-etl-processor),
entwickelt direkt auf `master` - kein separater Integrations-Branch) basiert auf
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

**Update, im Praxisbetrieb verifiziert und gefixt:** Die Vorgangsnummer wird zusätzlich als
genomDE-Transfer-TAN (`metadata.transferTan`) wiederverwendet, statt wie bei Paul eine
zweite, unabhängige Pseudonymisierung aus einer separaten gPAS-Multi-Pseudonym-Domäne
(`APP_PSEUDONYMIZE_GPAS_GENOM_DE_TAN_DOMAIN`, Default `ccdn`) anzufordern - diese Domäne war
bei uns nie angelegt, jeder `/mtb`-Aufruf schlug deshalb mit "db object for domain ccdn not
found" fehl. Die Vorgangsnummer erfüllt bereits alles, was eine Transfer-TAN braucht (frisch
pro Übertragung, über die Arbeitsnummer auf den Patienten rückführbar) - siehe KDoc von
`KeycloakGpasPseudonymGenerator`/`PseudonymizeService.genomDeTan()` sowie CLAUDE.md
"LMU fork gotchas".

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

Mittlerweile im Praxisbetrieb bestätigt: Die echte DIZ-Antwort ist ein FHIR-Bundle
(`type: searchset`), Policy-Codes passen exakt zu den Defaults in `DizConsentConfigProperties`.

**Hinweis:** Die Fall-ID wird pro Request-Zeile auch in der Monitoring-Oberfläche (`/`,
`/configs`) angezeigt (`fragments.html`, Feld "Fall-ID"). Ein Bug dort (Zugriff auf
`request.caseId.value` statt `request.caseId` - Kotlin-Inline-Value-Class, siehe CLAUDE.md
"LMU fork gotchas") liess die komplette Startseite mit einem Thymeleaf/SpEL-Fehler abstürzen,
sobald mindestens eine Request-Zeile existierte - ist inzwischen gefixt.

Aktivieren: `APP_CONSENT_SERVICE=diz_keycloak` (statt `gics`/`gics_get_bc`/`none`).

### 3. Geteilte Komponente: Keycloak-Token-Provider

Neu: [`KeycloakTokenProvider`](src/main/kotlin/dev/dnpm/etl/processor/keycloak/KeycloakTokenProvider.kt)
(package `dev.dnpm.etl.processor.keycloak`)

Client-Credentials-Flow per Default, cached den Access-Token bis kurz vor Ablauf. Zwei
Instanzen (gPAS, DIZ) mit eigenen Credentials, da unterschiedliche Keycloak-Clients/Realms.
DIZ braucht zusätzlich den Password-Grant (siehe oben) - dafür `username`/`password` im
`KeycloakClientConfig` setzen, sonst greift automatisch Client-Credentials (z.B. für gPAS,
dort bisher unverändert/unverifiziert).

**Bereits gefixter Stolperstein:** Keycloak-Token-Requests (gPAS UND DIZ) schlugen zunächst
mit "No HttpMessageConverter for ... LinkedMultiValueMap and content type
application/x-www-form-urlencoded" fehl - der geteilte `RestTemplate`-Bean in
`AppConfiguration.kt` hatte keinen `FormHttpMessageConverter` registriert
(`RestTemplateBuilder.messageConverters(...)` ersetzt Spring Boots Default-Konverter
komplett, statt sie zu ergänzen). Gefixt durch Hinzufügen von `FormHttpMessageConverter()`
zur Konverter-Liste.

### 4. TLS/Zertifikate

**Kein neuer Mechanismus nötig** - das Repo hat über Cloud Native Buildpacks (`bootBuildImage`)
bereits eine CA-Cert-Einspeisung (`bindings/ca-certificates/`, siehe
[`bindings/README.md`](bindings/README.md)). `BP_EMBED_CERTS=true` ist jetzt aktiviert
(`build.gradle.kts`). Die drei LMU-spezifischen Zertifikate (Keycloak-Intermediate,
gPAS-Selfsigned, DIZ-interne-CA - identisch zu denen aus dem Python-Prototyp) müssen nur noch
als `.pem` in `bindings/ca-certificates/` abgelegt werden, sobald ihr auf einem Rechner mit
Netzzugriff seid. Deckt automatisch alle ausgehenden HTTPS-Verbindungen ab (gPAS, DIZ, Keycloak,
DNPM:DIP), da alle HTTP-Clients hier das JVM-Standard-Truststore nutzen.

## Kompletten Reset nach Codeänderungen automatisieren

`./setup_app_for_testing.sh` fasst den Ablauf git pull → CSS/JS-Bundles bauen → ETL-Image
bauen → Testservice-Image bauen → beide (neu) starten in einem Befehl zusammen. Zieht per
Default den Branch `master` (überschreibbar per `BRANCH=... ./setup_app_for_testing.sh`, z.B.
um einen Feature-Branch vor dessen Merge zu testen - der Default zeigte lange auf einen
inzwischen veralteten Session-Branch, der still den Vor-Fix-Stand ausgerollt hat; falls das
Skript "nichts bewirkt", zuerst `BRANCH` in der Datei bzw. der Aufrufzeile prüfen). Setzt
eine bereits ausgefüllte `deploy/.env` voraus (siehe unten), verschiebt/erstellt sie aber
nicht. Proxy ist per Default auf `medwww.med.uni-muenchen.de:8080` gesetzt (siehe Variablen
am Kopf des Scripts) - bei Bedarf per Umgebungsvariable überschreiben, z.B.
`PROXY_HOST="" ./setup_app_for_testing.sh` für ein Netz ohne Proxy-Pflicht.

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
3. Das Testservice-Frontend zeigt inzwischen sowohl das tatsächlich gesendete MTB-JSON als
   auch das vom ETL weitergeleitete JSON groß und formatiert an (vorher: nur die - meist
   leere - HTTP-Antwort des ETL bzw. ein auf 4000 Zeichen abgeschnittener, unformatierter
   Empfangs-Body) - siehe `examples/dev/testservice/README.md`.

## Setup auf dem Zielsystem

**Stand:** Dieser komplette Weg wurde inzwischen erfolgreich gegen die echten LMU-Systeme
durchgespielt (echtes DIZ-Broad-Consent inkl. Rohantwort im Monitoring, echte
gPAS-Pseudonymisierung inkl. genomDE-Transfer-TAN). Die Schritte unten sind trotzdem als
Referenz stehen geblieben - für Re-Deploys auf neuen/zurückgesetzten Maschinen läuft man sie
komplett erneut durch.

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

## Ideen für später (Backlog)

**MV64-Dashboard in Onkostar:** Für Onkostar-Nutzer ist der komplette ETL-Durchlauf
(Pseudonymisierung, Consent, Versand, Antwort von DNPM:DIP) unsichtbar - insbesondere bei
Fehlern weiss niemand, welcher Patient/Fall schon vollständig übermittelt wurde. Wird
relevanter, sobald neben "initial" auch "correction" und "followup" dazukommen
(`submission_type` existiert dafür schon: INITIAL/ADDITION/CORRECTION/FOLLOWUP/TEST/UNKNOWN).

Idee: eigenes MV64-Dashboard-Formular in Onkostar, das beim ETL für einen Fall nachfragt, was
passiert ist. Wichtig dabei: Die Rückwärtssuche "PatientID → Pseudonym neu berechnen → in
`request` suchen" funktioniert bei `GPAS_KEYCLOAK` nicht zuverlässig, da die Vorgangsnummer
bei jedem Aufruf frisch erzeugt wird (nicht deterministisch, siehe oben). Die `case_id`
(`X-Case-Id`-Header) ist dagegen stabil und von Onkostar aus bekannt - der richtige Schlüssel
für so einen Lookup, nicht `patient_pseudonym`/`pid`.

Empfehlung: kein neues UI im ETL, sondern ein schlanker REST-Endpunkt (z.B.
`GET /api/case/{caseId}/status`), token-gesichert wie `/mtb`, den das Onkostar-Dashboard
abruft. `RequestRepository` bräuchte dafür noch eine Lookup-Methode nach `case_id` (gibt es
aktuell nicht). Offene Frage vor Umsetzung: kurz prüfen, ob der neue Datenkanal zurück zu
Onkostar (reine Status-Metadaten, kein Patientendateninhalt) datenschutzrechtlich unkritisch
ist.

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

Pauls Änderungen reinholen: `git fetch upstream && git merge upstream/master` auf `master`
(direkt - kein separater LMU-Branch, s.o.).
