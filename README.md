# ETL-Processor für das MV gem. §64e und DNPM:DIP

Dies ist ein Fork der des ETL-Processors von [Paul Volkmer](!https://github.com/pcvolkmer) für die 
Implementierung für das CCC der LMU München.

### Anpassungen gegenüber des Originals
* Authentifizierung
  * Keycloak implementierung
* Pseudonymisierung:
  * gPAS SOAP Schnittstelle
  * Pseudonymisierung über FallID
  * Zweistufige Pseudonymisierung
    * Pseudonymisierte PatID = Arbeitsnummer(FallID)
    * TransferTAN = Vorgangsnummer(Arbeitsnummer(FallID))
* Consent:
  * MV Consent: 
    * Wird nicht mehr über GICS abgerufen, wird über Export aus Onkostar angeliefert
    * Check auf permit on sequencing
  * Broad Consent
    * Wird über DIZ als fhir-Ressource geholt
    * Wird in Datenbank geschrieben


# Deployment
## Zertifikate und Proxy
Die Zertifikate kann man sich z.B. via Chrome von den entsprechenden Seiten holen und einfach 
einbinden über
``keytool -importcert -trustcacerts -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit -alias gpas-ca -file gpas-ca.crt``
wobei ``gpas-ca.crt`` das Zertifikat für den gPAS-Service ist. Ggf. müssen auch noch Keycloak- und Consentservicezertifikate hinzugefügt werden  
``changeit`` ist lediglich das default Passwort des Truststores


mtb.getPatient().setId(mtb.getPatient().getId()+"###"+caseId


