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

package dev.dnpm.etl.processor.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Config for the two-step, Keycloak-secured gPAS pseudonymization used at LMU:
 *
 * Fall-ID (X-Case-Id header) --(gPAS domain [arbeitsnummerDomain])--> Arbeitsnummer (= PatID
 * pseudonym) --(gPAS domain [vorgangsnummerDomain])--> Vorgangsnummer (= genomDE transfer TAN)
 *
 * The gPAS SOAP endpoint itself is configured via [GPasConfigProperties] (uri/soap-endpoint),
 * only the auth mechanism and the two domain names are specific to this generator.
 *
 * Auth verified against the real system: like DIZ's Keycloak
 * ([dev.dnpm.etl.processor.config.DizConsentConfigProperties]), gPAS's Keycloak realm requires
 * the resource-owner-password grant (client id/secret *and* a service account's
 * [username]/[password]), not plain client-credentials. Named without a "keycloak" prefix
 * (unlike Diz's flat `keycloakUsername`/`keycloakPassword`) since this class is already bound
 * under the `...gpas.keycloak` prefix - repeating it in the field name would double up in the
 * resulting env var name.
 */
@ConfigurationProperties(GpasKeycloakConfigProperties.NAME)
data class GpasKeycloakConfigProperties(
    val arbeitsnummerDomain: String = "arbeitsnummer",
    val vorgangsnummerDomain: String = "vorgangsnummer",
    /**
     * gPAS's 'arbeitsnummer' domain requires a case id of exactly this many digits. The Fall-ID
     * as delivered by Onkostar is usually shorter (typically 8 digits), so it gets left-padded
     * with zeros up to this length - this is *not* a fixed literal prefix.
     */
    val arbeitsnummerLength: Int = 10,
    val tokenUri: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
    /** Service-account username for gPAS's resource-owner-password Keycloak grant */
    val username: String? = null,
    /** Service-account password for gPAS's resource-owner-password Keycloak grant */
    val password: String? = null,
) {
    companion object {
        const val NAME = "app.pseudonymize.gpas.keycloak"
    }
}
