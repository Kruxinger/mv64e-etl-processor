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

import dev.dnpm.etl.processor.config.AppFhirConfig;
import dev.dnpm.etl.processor.config.DizConsentConfigProperties;
import dev.dnpm.etl.processor.keycloak.KeycloakTokenProvider;
import java.net.URISyntaxException;
import java.util.Date;
import org.apache.hc.core5.net.URIBuilder;
import org.hl7.fhir.r4.model.Bundle;
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
 * Requests Broad Consent from DIZ (LMU), which fronts a gICS instance with the same FHIR search
 * ({@code GET [uri]/Consent?...}) shape as upstream's {@link GicsGetBroadConsentService}, but
 * secured via Keycloak Bearer token instead of Basic-Auth.
 *
 * @since LMU fork
 */
@NullMarked
public class KeycloakDizConsentService extends AbstractConsentService {

  private final RetryTemplate retryTemplate;
  private final RestTemplate restTemplate;
  private final DizConsentConfigProperties config;
  private final KeycloakTokenProvider tokenProvider;

  public KeycloakDizConsentService(
      DizConsentConfigProperties config,
      RetryTemplate retryTemplate,
      RestTemplate restTemplate,
      KeycloakTokenProvider tokenProvider,
      AppFhirConfig appFhirConfig) {
    super(appFhirConfig.fhirContext(), LoggerFactory.getLogger(KeycloakDizConsentService.class));

    this.retryTemplate = retryTemplate;
    this.restTemplate = restTemplate;
    this.config = config;
    this.tokenProvider = tokenProvider;

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
    return fhirContext
        .newJsonParser()
        .parseResource(Bundle.class, requestResponse(personIdentifierValue));
  }

  @Nullable
  private String requestResponse(String personIdentifierValue) {
    if (null == this.config.getUri()) {
      throw new IllegalStateException("Missing DIZ consent URI configuration");
    }

    final var patientIdentifierQueryValue =
        "%s|%s".formatted(this.config.getPersonIdentifierSystem(), personIdentifierValue);

    try {
      final var uri =
          new URIBuilder(config.getUri())
              .appendPathSegments("Consent")
              .addParameter("domain:identifier", config.getBroadConsentDomainName())
              .addParameter(
                  "category",
                  "http://fhir.de/ConsentManagement/CodeSystem/ResultType|consent-status")
              .addParameter("patient.identifier", patientIdentifierQueryValue)
              .build();

      final var requestHeaders = new HttpHeaders();
      requestHeaders.setBearerAuth(tokenProvider.getAccessToken());

      final var response =
          this.retryTemplate.execute(
              retryContext ->
                  this.restTemplate.exchange(
                      uri, HttpMethod.GET, new HttpEntity<>(requestHeaders), String.class));
      if (response.getStatusCode().is2xxSuccessful()) {
        return response.getBody();
      } else {
        log.error(
            "DIZ consent system reached but request failed! code: '{}' response: '{}'",
            response.getStatusCode(),
            response.getBody());
        return null;
      }
    } catch (RestClientException e) {
      log.error("Get consent status request to DIZ failed reason: '{}'", e.getMessage());
      return null;
    } catch (TerminatedRetryException terminatedRetryException) {
      log.error(
          "Get consent status process to DIZ has been terminated. reason: '{}'",
          terminatedRetryException.getMessage());
      return null;
    } catch (URISyntaxException e) {
      log.error("Invalid URI for DIZ consent request: '{}'", e.getMessage());
      return null;
    }
  }

  @Override
  protected TtpConsentStatus evaluateConsentResponse(@Nullable String consentStatusResponse) {
    return MiiBroadConsentEvaluator.evaluate(this.fhirContext, consentStatusResponse);
  }
}
