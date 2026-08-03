# Hinweis für Root CA Zertifikate

PEM-Datei(en) in das Verzeichnis `ca-certificates` ablegen.

Die Datei `type` gibt dabei an, dass hier CA Zertifikate zu finden sind.

## LMU-Setup: die drei benötigten Zertifikate

Keycloak, gPAS und DIZ (siehe `APP_PSEUDONYMIZE_GPAS_KEYCLOAK_*` / `APP_CONSENT_DIZ_*` in
`deploy/env-sample.lmu.env`) sitzen im Uniklinikum-Netz hinter drei unterschiedlichen,
jeweils eigenwilligen TLS-Setups:

| Service  | Problem                                            | Lösung                                    |
|----------|-----------------------------------------------------|--------------------------------------------|
| Keycloak | Sendet nur das Leaf-Zertifikat, nicht das Intermediate (Issuer: GEANT TLS RSA 1 / HARICA, öffentliche Root, aber ohne Intermediate im Handshake bricht die Kettenprüfung) | Intermediate-Zertifikat der ausstellenden CA hier ablegen |
| gPAS     | Selbstsigniertes Zertifikat (Issuer == Subject), keine Kette | Nur Pinning möglich - bricht wieder, sobald gPAS das Zertifikat neu ausstellt (keine Vorwarnung) |
| DIZ      | Ausgestellt von der hausinternen PKI des Klinikums, taucht in keinem öffentlichen Trust-Store auf | Interne Root/Sub-CA-Zertifikate hier ablegen |

Vorgehen (identisch zum bereits bewährten Verfahren im Python-Prototyp):

1. Diagnose von einer Maschine mit Netzzugriff (z.B. per SSH auf dem Zielserver):
   ```bash
   openssl s_client -proxy <proxy-ip>:8080 -connect <host>:443 -servername <host> </dev/null 2>/dev/null | grep -A4 "Certificate chain"
   ```
   Issuer == Subject → selbstsigniert, nur Pinning möglich. Unbekannte/interne CA und nur ein
   Kettenglied → fehlendes Intermediate, per AIA-Extension nachschlagen:
   ```bash
   openssl s_client -proxy <proxy-ip>:8080 -connect <host>:443 -servername <host> </dev/null 2>/dev/null | openssl x509 -noout -text | grep -A2 "Authority Information Access"
   ```
2. Zertifikat besorgen (AIA-Link, oder bei rein internen CAs: IT/PKI-Team fragen bzw. aus dem
   Windows-Zertifikatsspeicher eines domänengebundenen Rechners exportieren).
3. Als `.pem` hier in `bindings/ca-certificates/` ablegen (Dateiname beliebig, z.B.
   `geant-tls-rsa-1-intermediate.pem`, `gpas-selfsigned.pem`, `diz-klinikum-subca-g3.pem`).
   Diese Dateien sind **nicht geheim** (öffentliche Zertifikate, kein Private Key) und dürfen
   ins Repo - `.gitignore` schließt aktuell nur `bindings/ca-certificates/*.pem` pauschal aus,
   das gilt es hier bewusst für die eigenen Zertifikate aufzuheben bzw. gezielt mit `git add -f`
   hinzuzufügen, falls sie dauerhaft im Fork mitgeführt werden sollen.
4. Beim Image-Build `BP_EMBED_CERTS=true` setzen (siehe `build.gradle.kts`, `bootBuildImage`),
   damit die Zertifikate zur Build-Zeit fest ins JVM-Truststore eingebettet werden. Das deckt
   dann automatisch **alle** ausgehenden HTTPS-Verbindungen ab (gPAS SOAP, DIZ/gICS REST,
   Keycloak-Token-Calls, DNPM:DIP REST) - kein Code- oder Env-Change nötig, da alle HTTP-Clients
   in diesem Projekt das JVM-Standard-Truststore verwenden.
5. Bei selbstsignierten Zertifikaten (gPAS): Fingerabdruck über einen vertrauenswürdigen Kanal
   verifizieren, bevor er gepinnt wird. Bricht garantiert wieder, sobald gPAS das Zertifikat neu
   generiert - dann Schritt 1-4 wiederholen.
