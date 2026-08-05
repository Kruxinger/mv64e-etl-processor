# ETL Test-Service

Kleines Flask-Tool zum manuellen End-to-End-Testen des `mv64e-etl-processor`, ohne echtes
Onkostar-Plugin und ohne echtes DNPM:DIP. Ein Button sendet ein komplexes, echtes
Mtb-Beispiel (`fixtures/mv64e-mtb-fake-patient.json`, Kopie aus `src/test/resources/` des
ETL-Repos - garantiert schema-valide) an den ETL. Dieselbe App spielt gleichzeitig
DNPM:DIP: Sie nimmt entgegen, was der ETL nach Pseudonymisierung/Consent-Pruefung/
Duplikaterkennung weiterleitet, und zeigt es im Frontend an.

Zusaetzlich spielt sie (LMU-Fork) einen Mock fuer DIZ/Keycloak, um den
`diz_keycloak`-Consent-Pfad zu testen, ohne dass das echte DIZ erreichbar sein muss.

## Starten

**Ohne Docker** (Python 3.11+):

```bash
cd examples/dev/testservice
pip install -r requirements.txt
python app.py
```

**Mit Docker:**

```bash
cd examples/dev/testservice
docker build -t etl-testservice .
docker run --rm -p 5000:5000 --add-host=host.docker.internal:host-gateway \
  -e ETL_BASE_URL=http://host.docker.internal:8000 etl-testservice
```

Danach: <http://localhost:5000>

## Wichtig: den ETL auf diesen Service zeigen lassen

Damit der "Empfangen"-Bereich im Frontend etwas anzeigt, muss der ETL seine Ausgabe
(`APP_REST_URI`) auf diesen Service richten, z. B. in `application-dev.yml`,
`.env`/`docker-compose` oder als Umgebungsvariable:

```
APP_REST_URI=http://localhost:5000
```

(Bei Docker-Compose-Betrieb statt `localhost` den Service-Namen dieses Containers nutzen.)

Ohne diese Umleitung sieht man im linken Panel trotzdem die synchrone Antwort des ETL auf
den POST-Request (202 Accepted / 400 Bad Request) - der eigentliche Rundlauf (rechtes Panel)
bleibt dann aber leer.

## Konfiguration (Umgebungsvariablen)

| Variable       | Default                  | Bedeutung                                  |
|----------------|---------------------------|---------------------------------------------|
| `ETL_BASE_URL` | `http://localhost:8000`  | Basis-URL des ETL (Port 8000 = `application-dev.yml`-Default) |
| `ETL_USERNAME` | `admin`                  | Basic-Auth-User fuer den `/mtb`-Endpoint des ETL |
| `ETL_PASSWORD` | `very-secret`             | Basic-Auth-Passwort (muss zur ETL-Konfiguration passen) |
| `PORT`         | `5000`                   | Port dieses Test-Service                    |

## Was die Buttons tun

- **An ETL senden**: POSTet das Fixture (mit der eingetragenen oder automatisch erzeugten
  Patient-ID) an `{ETL_BASE_URL}/mtb`.
- **Patient-ID**: leer lassen fuer automatisches Verhalten (siehe naechster Punkt), oder
  eine eigene ID eintragen - z.B. um dieselbe ID wiederholt mit/ohne Consent zu testen.
- **neue Patient-ID je Sendung**: nur relevant, wenn das Patient-ID-Feld leer ist.
  Standardmaessig an, damit jede Sendung als neuer Patient durchlaeuft. Abschalten, um
  mit derselben ID zweimal zu senden und die Duplikaterkennung des ETL zu testen.
- **Patient-ID hat Consent (Mock-DIZ)**: steuert, ob der Consent-Mock-Endpunkt
  (`/Consent`, siehe unten) fuer die gerade verwendete Patient-ID einen aktiven Broad
  Consent zurueckgibt oder eine leere Bundle (= kein Consent gefragt). Die
  Markierung bleibt bestehen, bis sie fuer dieselbe ID wieder abgewaehlt wird oder
  "Liste leeren" gedrueckt wird.
- **Simulierte DIP-Antwort**: bestimmt, mit welchem `issues`-Report (siehe
  `ReportService.Severity` im ETL) dieser Mock-Empfaenger auf die naechste eingehende
  Weiterleitung antwortet - so laesst sich SUCCESS/WARNING/ERROR/DUPLICATION-Handling im
  ETL beobachten, ohne ein echtes DNPM:DIP zu brauchen.

## DIZ/Keycloak-Consent-Mock (LMU-Fork, `app.consent.service=diz_keycloak`)

**Wichtig:** DIZ' Broad Consent ist ueber die **Fall-ID** verknuepft, nicht ueber
`patient.id` im Mtb-JSON (das ist die FallnummerMV - eine andere ID). Die Fall-ID wird
per `X-Case-Id`-Header uebertragen. Dieser Testservice sendet beim "An ETL senden"-Button
denselben Wert aus dem Patient-/Fall-ID-Feld sowohl als `patient.id` im JSON als auch als
`X-Case-Id`-Header - `ConsentProcessor` im ETL nutzt bevorzugt den Header.

Damit sich `KeycloakDizConsentService` gegen diesen Mock statt gegen das echte DIZ testen
laesst, in der `.env` (siehe `deploy/env-sample.lmu.env`):

```
APP_CONSENT_SERVICE=diz_keycloak
APP_CONSENT_DIZ_URI=http://host.docker.internal:5000/Consent?patient=
APP_CONSENT_DIZ_KEYCLOAKTOKENURI=http://host.docker.internal:5000/mock-keycloak/token
APP_CONSENT_DIZ_KEYCLOAKCLIENTID=mock-client
APP_CONSENT_DIZ_KEYCLOAKCLIENTSECRET=mock-secret
APP_CONSENT_DIZ_KEYCLOAKUSERNAME=mock-user
APP_CONSENT_DIZ_KEYCLOAKPASSWORD=mock-password
```

(Bei Docker-Compose-Betrieb statt `host.docker.internal` den Service-Namen dieses
Containers nutzen; alle Werte sind beliebig, der Mock prueft weder Grant-Type noch
Credentials. Wichtig: `APP_CONSENT_DIZ_URI` muss mit `/Consent?patient=` enden - der ETL
haengt die Patient-ID direkt an, siehe naechster Punkt.)

Zwei zusaetzliche Endpunkte bedienen diesen Pfad:

- `POST /mock-keycloak/token`: nimmt jeden Token-Request an (egal ob `client_credentials`
  oder `password`-Grant) und antwortet mit einem festen Fake-Access-Token - genug, damit
  `KeycloakTokenProvider` einen Bearer-Header setzen kann, ohne dass ein echtes Keycloak
  involviert ist.
- `GET /Consent?patient=<PatID>`: mockt DIZ' Consent-Endpunkt. Verifiziert gegen das echte
  System ist das ein simples GET mit direkt angehaengter Patient-ID, kein FHIR-Search mit
  separaten Parametern (das war die urspruengliche, in `LMU-README.md` dokumentierte
  Annahme - inzwischen widerlegt und im ETL-Code korrigiert). Liefert eine
  Bundle mit einem aktiven, `permit`-Consent (Code/System aus
  `CONSENT_POLICY_CODE`/`CONSENT_POLICY_SYSTEM`, Default = MII-Broad-Consent-Defaults der
  ETL-Config) fuer Patient-IDs, die im Frontend als "hat Consent" markiert wurden, sonst eine
  leere Bundle.

Das dritte Panel im Frontend ("DIZ Consent (Mock)") zeigt die letzte eingehende
Consent-Abfrage (angefragte Patient-ID, ob ein Bearer-Header dabei war, ob ein Consent
gefunden wurde) sowie die Liste aller aktuell markierten Patient-IDs. Der ETL-Processor
selbst zeigt seinerseits in seiner eigenen Monitoring-Oberflaeche
(`/configs`, Kachel "Letzte DIZ Consent-Abfrage") die aus seiner Sicht letzte Anfrage inkl.
Rohantwort - so laesst sich der Rundlauf von beiden Seiten pruefen.

## Grenzen

- Testet den REST-Ein-/Ausgang und die generelle Pipeline (Pseudonymisierung, Consent,
  Duplikaterkennung, Weiterleitung) inklusive des `diz_keycloak`-Consent-Pfads. Der
  Keycloak-gesicherte gPAS-Pseudonymisierungspfad laesst sich damit nicht sinnvoll
  durchspielen, da das echte gPAS nur aus dem Uniklinikum-Netz erreichbar ist - dafuer
  bleibt nur der Test auf dem Zielsystem.
- Der Consent-Mock prueft weder den Inhalt des Bearer-Tokens noch, ob der Grant-Type wirklich
  `password` ist - er reagiert rein auf die Patient-ID im `patient`-Query-Parameter. Das
  Request-*Format* (URL-Form, Grant-Type) ist inzwischen gegen das echte System verifiziert;
  offen bleibt nur, ob die exakte Response-*Struktur* (Bundle vs. einzelne Consent-Resource,
  Policy-Codes) zu dem passt, was `ConsentProcessor` erwartet - das laesst sich nur mit einer
  echten Antwort vom Zielsystem abschliessend pruefen.
- Kein Auth-Schutz auf diesem Service selbst, kein Threading-Hardening ueber Flasks
  Dev-Server hinaus - bewusst nur fuer lokale Entwicklung, nicht fuer produktiven Einsatz.
