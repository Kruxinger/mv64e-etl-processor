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

package dev.dnpm.etl.processor.keycloak

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.test.web.client.ExpectedCount
import org.springframework.test.web.client.MockRestServiceServer
import org.springframework.test.web.client.match.MockRestRequestMatchers.method
import org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo
import org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess
import org.springframework.web.client.RestTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class KeycloakTokenProviderTest {
    private val clientConfig =
        KeycloakClientConfig(
            tokenUri = "http://localhost/realms/mv64e/protocol/openid-connect/token",
            clientId = "etl-processor",
            clientSecret = "secret",
        )

    @Test
    fun shouldFetchAndCacheToken() {
        val restTemplate = RestTemplate()
        val mockServer = MockRestServiceServer.createServer(restTemplate)
        val clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)

        mockServer
            .expect(ExpectedCount.once(), requestTo(clientConfig.tokenUri))
            .andExpect(method(org.springframework.http.HttpMethod.POST))
            .andRespond(
                withSuccess(
                    """{"access_token":"token-1","expires_in":300}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val provider = KeycloakTokenProvider(clientConfig, restTemplate, clock)

        assertThat(provider.getAccessToken()).isEqualTo("token-1")
        // second call within validity window must be served from cache, not a second HTTP call
        assertThat(provider.getAccessToken()).isEqualTo("token-1")

        mockServer.verify()
    }

    @Test
    fun shouldRefreshTokenAfterExpiry() {
        val restTemplate = RestTemplate()
        val mockServer = MockRestServiceServer.createServer(restTemplate)
        val mutableClock = MutableClock(Instant.parse("2026-01-01T00:00:00Z"))

        // both expectations declared upfront, in the order the calls below will trigger them
        mockServer
            .expect(ExpectedCount.once(), requestTo(clientConfig.tokenUri))
            .andRespond(
                withSuccess(
                    """{"access_token":"token-1","expires_in":60}""",
                    MediaType.APPLICATION_JSON,
                ),
            )
        mockServer
            .expect(ExpectedCount.once(), requestTo(clientConfig.tokenUri))
            .andRespond(
                withSuccess(
                    """{"access_token":"token-2","expires_in":300}""",
                    MediaType.APPLICATION_JSON,
                ),
            )

        val provider = KeycloakTokenProvider(clientConfig, restTemplate, mutableClock)
        assertThat(provider.getAccessToken()).isEqualTo("token-1")

        // advance past the 30s safety margin baked into expires_in=60
        mutableClock.advanceSeconds(120)

        assertThat(provider.getAccessToken()).isEqualTo("token-2")
        mockServer.verify()
    }
}

private class MutableClock(
    private var instant: Instant,
) : Clock() {
    fun advanceSeconds(seconds: Long) {
        instant = instant.plusSeconds(seconds)
    }

    override fun getZone(): ZoneOffset = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId): Clock = this

    override fun instant(): Instant = instant
}
