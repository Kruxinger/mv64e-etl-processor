# ETL Test-Service

Kleines Flask-Tool zum manuellen End-to-End-Testen des `mv64e-etl-processor`, ohne echtes
Onkostar-Plugin und ohne echtes DNPM:DIP. Ein Button sendet ein komplexes, echtes
Mtb-Beispiel (`fixtures/mv64e-mtb-fake-patient.json`, Kopie aus `src/test/resources/` des
ETL-Repos - garantiert schema-valide) an den ETL. Dieselbe App spielt gleichzeitig
DNPM:DIP: Sie nimmt entgegen, was der ETL nach Pseudonymisierung/Consent-Pruefung/
Duplikaterkennung weiterleitet, und zeigt es im Frontend an.

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

- **An ETL senden**: POSTet das Fixture (optional mit frisch generierter Patient-ID) an
  `{ETL_BASE_URL}/mtb`.
- **neue Patient-ID je Sendung**: standardmaessig an, damit jede Sendung als neuer Patient
  durchlaeuft. Abschalten, um mit derselben ID zweimal zu senden und die
  Duplikaterkennung des ETL zu testen.
- **Simulierte DIP-Antwort**: bestimmt, mit welchem `issues`-Report (siehe
  `ReportService.Severity` im ETL) dieser Mock-Empfaenger auf die naechste eingehende
  Weiterleitung antwortet - so laesst sich SUCCESS/WARNING/ERROR/DUPLICATION-Handling im
  ETL beobachten, ohne ein echtes DNPM:DIP zu brauchen.

## Grenzen

- Testet nur den REST-Ein-/Ausgang und die generelle Pipeline (Pseudonymisierung, Consent,
  Duplikaterkennung, Weiterleitung). Die LMU-spezifischen Keycloak-gesicherten Pfade
  (gPAS/DIZ) lassen sich damit nicht sinnvoll durchspielen, da die echten Systeme nur aus
  dem Uniklinikum-Netz erreichbar sind - dafuer bleibt nur der Test auf dem Zielsystem.
- Kein Auth-Schutz auf diesem Service selbst, kein Threading-Hardening ueber Flasks
  Dev-Server hinaus - bewusst nur fuer lokale Entwicklung, nicht fuer produktiven Einsatz.
