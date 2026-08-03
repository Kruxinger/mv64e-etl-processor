/*
 * This file is part of ETL-Processor
 *
 * Copyright (c) 2026  LMU Klinikum, Datenintegrationszentrum
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dev.dnpm.etl.processor.consent;

import ca.uhn.fhir.parser.DataFormatException;
import dev.dnpm.etl.processor.config.AppFhirConfig;
import dev.dnpm.etl.processor.config.DizConsentConfigProperties;
import dev.dnpm.etl.processor.keycloak.KeycloakTokenProvider;
import java.net.URI;
import java.util.Date;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Consent;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.retry.TerminatedRetryException;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Requests Broad Consent from DIZ (LMU), secured via Keycloak (resource-owner-password grant,
 * see {@link KeycloakTokenProvider}).
 *
 * <p>Request shape verified against the real system: a plain {@code GET [uri]<patientId>} with
 * the patient id appended directly - <b>not</b> a FHIR search with separate query params like
 * upstream's {@link GicsGetBroadConsentService}. {@link DizConsentConfigProperties#getUri()} is
 * therefore expected to already include everything up to the insertion point, e.g. {@code
 * https://bc.diz.med.uni-muenchen.de/fhir/Consent?patient=}. The response is parsed leniently as
 * either a Bundle or a bare Consent resource, since which of the two DIZ actually returns wasn't
 * confirmed at the time of writing.
 *
 * @since LMU fork
 */
@NullMarked
public class KeycloakDizConsentService extends AbstractConsentService {

  private final RetryTemplate retryTemplate;
  private final RestTemplate restTemplate;
  private final DizConsentConfigProperties config;
  private final KeycloakTokenProvider tokenProvider;
  private final DizConsentInspection dizConsentInspection;

  public KeycloakDizConsentService(
      DizConsentConfigProperties config,
      RetryTemplate retryTemplate,
      RestTemplate restTemplate,
      KeycloakTokenProvider tokenProvider,
      AppFhirConfig appFhirConfig,
      DizConsentInspection dizConsentInspection) {
    super(appFhirConfig.fhirContext(), LoggerFactory.getLogger(KeycloakDizConsentService.class));

    this.retryTemplate = retryTemplate;
    this.restTemplate = restTemplate;
    this.config = config;
    this.tokenProvider = tokenProvider;
    this.dizConsentInspection = dizConsentInspection;

    if (null == this.config.getUri()) {
      throw new IllegalStateException("Missing DIZ consent URI configuration");
    }

    log.info("KeycloakDizConsentService initialized...");
  }

  @Override
  public TtpConsentStatus getTtpBroadConsentStatus(String personIdentifierValue) {
    return evaluateConsentResponse(requestResponse(personIdentifierValue));
  }

  @Override
  public Bundle getConsent(
      String personIdentifierValue, Date requestDate, ConsentDomain consentDomain) {
    return parseAsBundle(requestResponse(personIdentifierValue));
  }

  @Nullable
  private String requestResponse(String personIdentifierValue) {
    if (null == this.config.getUri()) {
      throw new IllegalStateException("Missing DIZ consent URI configuration");
    }

    try {
      // patient id appended directly - assumed URL-safe (numeric FallnummerMV-derived value),
      // matching the verified reference implementation exactly rather than guessing at encoding
      final var uri = URI.create(config.getUri() + personIdentifierValue);

      final var requestHeaders = new HttpHeaders();
      requestHeaders.setBearerAuth(tokenProvider.getAccessToken());

      final var response =
          this.retryTemplate.execute(
              retryContext ->
                  this.restTemplate.exchange(
                      uri, HttpMethod.GET, new HttpEntity<>(requestHeaders), String.class));
      if (response.getStatusCode().is2xxSuccessful()) {
        final var body = response.getBody();
        dizConsentInspection.record(
            true, personIdentifierValue, !parseAsBundle(body).getEntry().isEmpty(), body);
        return body;
      } else {
        log.error(
            "DIZ consent system reached but request failed! code: '{}' response: '{}'",
            response.getStatusCode(),
            response.getBody());
        dizConsentInspection.record(true, personIdentifierValue, false, response.getBody());
        return null;
      }
    } catch (RestClientException e) {
      log.error("Get consent status request to DIZ failed reason: '{}'", e.getMessage());
      dizConsentInspection.record(false, personIdentifierValue, false, e.getMessage());
      return null;
    } catch (TerminatedRetryException terminatedRetryException) {
      log.error(
          "Get consent status process to DIZ has been terminated. reason: '{}'",
          terminatedRetryException.getMessage());
      dizConsentInspection.record(
          false, personIdentifierValue, false, terminatedRetryException.getMessage());
      return null;
    } catch (IllegalArgumentException e) {
      log.error("Invalid URI for DIZ consent request: '{}'", e.getMessage());
      dizConsentInspection.record(false, personIdentifierValue, false, e.getMessage());
      return null;
    }
  }

  /**
   * Parses the raw response leniently: a Bundle is returned as-is, a bare Consent resource gets
   * wrapped into a single-entry Bundle, so {@link #getConsent} always returns a uniform Bundle
   * shape regardless of which one DIZ actually sends. Null body, unparseable JSON, or an
   * unexpected resource type all fall back to an empty Bundle - consistent with "not asked",
   * which is how ConsentProcessor already treats an empty entry list.
   */
  private Bundle parseAsBundle(@Nullable String json) {
    if (null == json) {
      return new Bundle();
    }
    try {
      final var resource = fhirContext.newJsonParser().parseResource(json);
      if (resource instanceof Bundle bundle) {
        return bundle;
      }
      if (resource instanceof Consent consent) {
        final var bundle = new Bundle();
        bundle.addEntry().setResource(consent);
        return bundle;
      }
      log.error("Unexpected DIZ consent response resource type: '{}'", resource.getClass());
      return new Bundle();
    } catch (DataFormatException e) {
      log.error("Failed to parse DIZ consent response as FHIR resource: '{}'", e.getMessage());
      return new Bundle();
    }
  }

  @Override
  protected TtpConsentStatus evaluateConsentResponse(@Nullable String consentStatusResponse) {
    return MiiBroadConsentEvaluator.evaluate(this.fhirContext, consentStatusResponse);
  }
}
