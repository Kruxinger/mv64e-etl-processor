package dev.dnpm.etl.processor.consent;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import dev.dnpm.etl.processor.services.KeycloakTokenService;
import org.hl7.fhir.r4.model.Bundle;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Date;


public class DizConsentService implements IConsentService {

    private final RestTemplate restTemplate;
    private final KeycloakTokenService tokenService;
    private final FhirContext fhirContext;
    @Value("${diz-consent.endpoint}")
    private String dizEndpoint;

    public DizConsentService(RestTemplate restTemplate,
                             KeycloakTokenService tokenService) {
        this.restTemplate = restTemplate;
        this.tokenService = tokenService;
        this.fhirContext = FhirContext.forR4(); // HAPI FHIR R4
    }

    @Override
    public TtpConsentStatus getTtpBroadConsentStatus(String personIdentifierValue) {
        // Optional: einfacher Statuscheck, kann auf "GIVEN" setzen
        return TtpConsentStatus.BROAD_CONSENT_GIVEN;
    }

    @Override
    public Bundle getConsent(String patientId, Date requestDate, ConsentDomain consentDomain) {
        try {
            // Keycloak Token holen
            String token = tokenService.getToken("diz");

            // URL zum FHIR Consent Service
            String url = dizEndpoint + patientId;

            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(token);
            headers.setAccept(MediaType.parseMediaTypes("application/fhir+json"));
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Void> entity = new HttpEntity<>(headers);

            // REST-Call ausführen
            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new RuntimeException("Consent-Service returned status " + response.getStatusCode());
            }

            String responseBody = response.getBody();
            if (responseBody == null || responseBody.isEmpty()) {
                throw new RuntimeException("Consent-Service returned empty response for patient " + patientId);
            }

            // JSON-Antwort in FHIR Bundle parsen
            IParser parser = fhirContext.newJsonParser();
            Bundle bundle = parser.parseResource(Bundle.class, responseBody);

            return bundle;

        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch consent for patient " + patientId, e);
        }
    }
}
