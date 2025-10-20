package dev.dnpm.etl.processor.services

import dev.dnpm.etl.processor.config.KeycloakProperties
import org.springframework.web.client.RestTemplate
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import java.util.Base64

@Service
class KeycloakTokenService(
    private val restTemplate: RestTemplate,
    private val keycloakProperties: KeycloakProperties
) {

    fun getToken(client: String): String {
        val props = when (client.lowercase()) {
            "gpas" -> keycloakProperties.gpas
            "diz" -> keycloakProperties.diz
            else -> throw IllegalArgumentException("Unknown Keycloak client: $client")
        }

        val headers = HttpHeaders()
        headers.contentType = MediaType.APPLICATION_FORM_URLENCODED

        val body = mapOf(
            "client_id" to props.clientId,
            "client_secret" to props.clientSecret,
            "username" to props.username,
            "password" to props.password,
            "grant_type" to "password"
        ).map { "${it.key}=${it.value}" }.joinToString("&")

        val entity = HttpEntity(body, headers)
        val response = restTemplate.postForObject(props.tokenUrl, entity, Map::class.java)

        @Suppress("UNCHECKED_CAST")
        return response?.get("access_token") as? String
            ?: throw IllegalStateException("Failed to get Keycloak token for client ${props.clientId}")
    }
}
