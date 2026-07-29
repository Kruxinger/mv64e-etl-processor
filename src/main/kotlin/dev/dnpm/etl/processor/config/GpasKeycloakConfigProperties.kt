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
 * FallnummerMV --(gPAS domain [arbeitsnummerDomain])--> Arbeitsnummer
 * --(gPAS domain [vorgangsnummerDomain])--> Vorgangsnummer (= final PatID pseudonym)
 *
 * The gPAS SOAP endpoint itself is configured via [GPasConfigProperties] (uri/soap-endpoint),
 * only the auth mechanism and the two domain names are specific to this generator.
 */
@ConfigurationProperties(GpasKeycloakConfigProperties.NAME)
data class GpasKeycloakConfigProperties(
    val arbeitsnummerDomain: String = "arbeitsnummer",
    val vorgangsnummerDomain: String = "vorgangsnummer",
    /** Prefix required by gPAS for the arbeitsnummer domain, prepended if not already present. */
    val arbeitsnummerPrefix: String = "00",
    val tokenUri: String? = null,
    val clientId: String? = null,
    val clientSecret: String? = null,
) {
    companion object {
        const val NAME = "app.pseudonymize.gpas.keycloak"
    }
}
