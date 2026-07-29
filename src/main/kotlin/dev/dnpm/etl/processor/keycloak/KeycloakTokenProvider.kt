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

import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.util.LinkedMultiValueMap
import org.springframework.web.client.RestTemplate
import java.time.Clock
import java.time.Instant

/**
 * Client-credentials config for a single Keycloak client (one per downstream service, since gPAS
 * and DIZ may live in different Keycloak realms/clients).
 */
data class KeycloakClientConfig(
    val tokenUri: String,
    val clientId: String,
    val clientSecret: String,
)

/**
 * Fetches and caches an OAuth2 client-credentials access token for a single Keycloak client.
 *
 * One instance per downstream service (gPAS, DIZ) - tokens are not shared across clients. Renews
 * the token shortly before actual expiry to avoid races where a cached-but-about-to-expire token
 * is handed to a slow downstream call.
 */
class KeycloakTokenProvider(
    private val clientConfig: KeycloakClientConfig,
    private val restTemplate: RestTemplate,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val logger = LoggerFactory.getLogger(KeycloakTokenProvider::class.java)

    private val lock = Any()
    private var cachedToken: CachedToken? = null

    private data class CachedToken(
        val accessToken: String,
        val expiresAt: Instant,
    )

    fun getAccessToken(): String {
        synchronized(lock) {
            val cached = cachedToken
            if (cached != null && cached.expiresAt.isAfter(Instant.now(clock))) {
                return cached.accessToken
            }
            return requestNewToken().also { cachedToken = it }.accessToken
        }
    }

    private fun requestNewToken(): CachedToken {
        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED

        val body = LinkedMultiValueMap<String, String>()
        body.add("grant_type", "client_credentials")
        body.add("client_id", clientConfig.clientId)
        body.add("client_secret", clientConfig.clientSecret)

        val response =
            restTemplate.exchange(
                clientConfig.tokenUri,
                HttpMethod.POST,
                org.springframework.http.HttpEntity(body, headers),
                Map::class.java,
            )

        // parsed as a plain Map, not a data class, so we don't depend on which Jackson
        // flavour/naming-strategy is wired into the shared RestTemplate's message converters
        val tokenResponse =
            response.body
                ?: throw IllegalStateException(
                    "Keycloak token request to '${clientConfig.tokenUri}' returned no body",
                )

        val accessToken =
            tokenResponse["access_token"] as? String
                ?: throw IllegalStateException(
                    "Keycloak token response from '${clientConfig.tokenUri}' has no 'access_token'",
                )
        val expiresIn = (tokenResponse["expires_in"] as? Number)?.toLong() ?: 60L

        logger.debug("Obtained new Keycloak access token, valid for {}s", expiresIn)

        // renew 30s before actual expiry to avoid handing out tokens that expire mid-flight
        val safetyMarginSeconds = 30L
        val validSeconds = (expiresIn - safetyMarginSeconds).coerceAtLeast(0L)
        return CachedToken(
            accessToken = accessToken,
            expiresAt = Instant.now(clock).plusSeconds(validSeconds),
        )
    }
}
