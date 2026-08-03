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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import dev.dnpm.etl.processor.config.AppFhirConfig;
import dev.dnpm.etl.processor.config.DizConsentConfigProperties;
import dev.dnpm.etl.processor.keycloak.KeycloakTokenProvider;
import dev.dnpm.etl.processor.monitoring.ConnectionCheckResult;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.apache.commons.io.IOUtils;
import org.apache.hc.core5.net.URIBuilder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Sinks;

@ExtendWith(MockitoExtension.class)
class KeycloakDizConsentServiceTest {

  static final String DIZ_BASE_URI = "http://localhost:8090/diz-fhir/fhir";

  @Mock KeycloakTokenProvider tokenProvider;

  MockRestServiceServer mockRestServiceServer;
  DizConsentConfigProperties config;
  KeycloakDizConsentService service;

  static URI expectedConsentEndpoint() throws Exception {
    return new URIBuilder(URI.create(DIZ_BASE_URI))
        .appendPath("/Consent")
        .addParameter("domain:identifier", "MII")
        .addParameter(
            "category", "http://fhir.de/ConsentManagement/CodeSystem/ResultType|consent-status")
        .addParameter(
            "patient.identifier",
            "https://ths-greifswald.de/fhir/gics/identifiers/Patienten-ID|123456")
        .build();
  }

  @BeforeEach
  void setUp() {
    this.config = new DizConsentConfigProperties(DIZ_BASE_URI);

    var restTemplate = new RestTemplate();
    this.mockRestServiceServer = MockRestServiceServer.createServer(restTemplate);
    this.service =
        new KeycloakDizConsentService(
            this.config,
            RetryTemplate.builder().maxAttempts(1).build(),
            restTemplate,
            this.tokenProvider,
            new AppFhirConfig(),
            new DizConsentInspection(
                Sinks.<ConnectionCheckResult>many().multicast().onBackpressureBuffer()));
  }

  @Test
  void shouldSendBearerTokenAndReturnConsentStatus() throws Exception {
    when(tokenProvider.getAccessToken()).thenReturn("test-token");

    var inputStream =
        Objects.requireNonNull(
            this.getClass()
                .getClassLoader()
                .getResourceAsStream("fake_broadConsent_mii_response_permit.json"));

    mockRestServiceServer
        .expect(requestTo(expectedConsentEndpoint()))
        .andExpect(header("Authorization", "Bearer test-token"))
        .andRespond(
            withSuccess(
                IOUtils.toString(inputStream, StandardCharsets.UTF_8), MediaType.APPLICATION_JSON));

    var consentStatus = service.getTtpBroadConsentStatus("123456");
    assertThat(consentStatus).isEqualTo(TtpConsentStatus.BROAD_CONSENT_GIVEN);
  }

  @Test
  void shouldReturnFailedToAskOnServerError() throws Exception {
    when(tokenProvider.getAccessToken()).thenReturn("test-token");

    mockRestServiceServer
        .expect(requestTo(expectedConsentEndpoint()))
        .andRespond(withServerError());

    var consentStatus = service.getTtpBroadConsentStatus("123456");
    assertThat(consentStatus).isEqualTo(TtpConsentStatus.FAILED_TO_ASK);
  }
}
